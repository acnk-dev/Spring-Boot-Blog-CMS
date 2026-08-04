# Commit 40 — Async Thread Pool Configuration

## Overview

This commit introduces `AsyncConfig`, a Spring configuration class that enables asynchronous method execution across the application and backs it with a properly sized, production-ready thread pool. Before this change, calling `@Async` on any method would have either failed silently (if `@EnableAsync` was absent) or used Spring's default `SimpleAsyncTaskExecutor`, which spawns an unbounded new thread for every invocation — a pattern that causes thread exhaustion under load. `AsyncConfig` replaces that default with a `ThreadPoolTaskExecutor` whose size, queue depth, rejection behaviour, and shutdown grace period are all configurable via `application.properties`.

---

## Problem / Motivation

### What happens without this class

Spring's `@Async` annotation requires two things to work:

1. `@EnableAsync` must be present somewhere in the application context.
2. An `Executor` bean must be available to actually run the tasks.

If neither is provided and code calls an `@Async` method, the call is executed **synchronously on the caller's thread** — the annotation is effectively ignored. If `@EnableAsync` is present but no custom executor is declared, Spring falls back to `SimpleAsyncTaskExecutor`.

### The problem with `SimpleAsyncTaskExecutor`

`SimpleAsyncTaskExecutor` does exactly what its name says: for every submitted task it creates a **new OS thread**, runs the task, then discards the thread. This is fine for development with a handful of requests. It becomes dangerous in production because:

- Thread creation is expensive (each thread allocates a stack, typically 512 KB–1 MB).
- There is no upper bound on concurrent threads. A burst of 500 requests that each trigger an async task creates 500 threads simultaneously.
- The JVM and OS have hard limits on thread counts. Exceeding them causes `OutOfMemoryError: unable to create native thread`.
- There is no queue to absorb bursts — work either runs immediately or fails.

A thread pool solves all of these problems by keeping a fixed set of reusable threads and queuing work that arrives when all threads are busy.

---

## Decisions Made

### Decision 1: `ThreadPoolTaskExecutor` vs `SimpleAsyncTaskExecutor`

**Option A — `SimpleAsyncTaskExecutor` (default)**

No configuration needed. Spring uses it automatically when no custom executor is declared.

*Rejected because:* As explained above, it creates one thread per task with no upper bound, making it unsuitable for any production workload.

**Option B — `ThreadPoolTaskExecutor` (chosen)**

Spring's wrapper around the JDK's `ThreadPoolExecutor`. Provides core pool size, max pool size, a bounded queue, configurable rejection policy, named threads, and graceful shutdown — all controllable via properties.

*Chosen because:* It is the standard production choice for Spring async work. It prevents thread exhaustion, gives meaningful thread names for log tracing, and can be tuned without recompiling.

---

### Decision 2: `CallerRunsPolicy` vs other rejection policies

When the queue is full and all threads are busy, a rejected execution handler decides what to do with the incoming task. The JDK ships four options.

**Option A — `AbortPolicy` (JDK default)**

Throws `RejectedExecutionException`. The task is dropped and the exception propagates to the caller.

*Rejected because:* For background tasks like sending emails or logging view counts, silently crashing with an exception is worse than running the task slowly. The caller gets an unexpected exception it may not handle.

**Option B — `DiscardPolicy`**

Silently drops the task. No exception, no notification.

*Rejected because:* Even worse than AbortPolicy — the work disappears with no trace. Debugging why emails were never sent becomes nearly impossible.

**Option C — `DiscardOldestPolicy`**

Drops the oldest queued task to make room for the new one.

*Rejected because:* Still loses work, and preferentially abandons the tasks that have been waiting longest, which is backwards from a fairness standpoint.

**Option D — `CallerRunsPolicy` (chosen)**

The thread that submitted the task runs it synchronously. The caller blocks until the task finishes, then continues.

*Chosen because:* It creates natural back-pressure. When the pool is saturated, the HTTP request thread that triggered the async call slows down instead of accepting more work. This prevents the system from accepting requests faster than it can process them, and no work is ever silently dropped. For a blog CMS where async tasks are best-effort background work (email, view logging, image processing), this is the correct trade-off.

---

### Decision 3: `implements AsyncConfigurer` vs declaring a plain `@Bean`

**Option A — Declare a `@Bean` named `"taskExecutor"`**

```java
@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() { ... }
}
```

Spring's `@Async` looks for a bean named `"taskExecutor"` by convention. Declaring a bean with that name works.

**Option B — `implements AsyncConfigurer` (chosen)**

```java
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {
    @Override
    public Executor getAsyncExecutor() { ... }
}
```

`AsyncConfigurer` is a Spring interface with two methods: `getAsyncExecutor()` and `getAsyncUncaughtExceptionHandler()`. Implementing it makes the intent explicit in the type system — anyone reading the class immediately understands it is the async configuration entry point, not just an arbitrary bean producer. It also allows overriding `getAsyncUncaughtExceptionHandler()` later without changing the class structure.

*Chosen because:* Clearer intent, better extensibility. The `@Bean(name = "taskExecutor")` annotation is retained as well so the executor can also be injected by name if a specific component needs it.

---

## Changes Made

### `src/main/java/com/studylink/blogcms/config/AsyncConfig.java` (new file)

```java
@Configuration
@EnableAsync                          // (1) activates Spring's async proxy infrastructure
public class AsyncConfig implements AsyncConfigurer {

    @Value("${async.core-pool-size:4}")       // (2) default fallback after the colon
    private int corePoolSize;

    @Value("${async.max-pool-size:16}")
    private int maxPoolSize;

    @Value("${async.queue-capacity:200}")
    private int queueCapacity;

    @Value("${async.thread-name-prefix:blogcms-async-}")
    private String threadNamePrefix;

    @Bean(name = "taskExecutor")
    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);    // (3) always-on threads
        executor.setMaxPoolSize(maxPoolSize);       // (4) burst ceiling
        executor.setQueueCapacity(queueCapacity);  // (5) buffer between core and max
        executor.setThreadNamePrefix(threadNamePrefix); // (6) for log filtering
        executor.setRejectedExecutionHandler(
            new ThreadPoolExecutor.CallerRunsPolicy()); // (7) back-pressure
        executor.setWaitForTasksToCompleteOnShutdown(true); // (8) graceful drain
        executor.setAwaitTerminationSeconds(30);   // (9) max wait before forced exit
        executor.initialize();                     // (10) must call before returning
        return executor;
    }
}
```

Annotated explanations:

1. `@EnableAsync` installs a Spring AOP proxy that intercepts calls to `@Async`-annotated methods and redirects them to the executor. Without this annotation the executor bean exists but `@Async` does nothing.
2. The colon syntax in `@Value` provides a default: if the property is absent from `application.properties`, the value after the colon is used. This prevents startup failures in environments where the properties file has not been updated yet.
3. **Core pool size** — the number of threads kept alive permanently, even when idle. Work arriving when all core threads are busy goes into the queue first.
4. **Max pool size** — the total thread count ceiling. New threads beyond the core count are created only after the queue is full. They are terminated after being idle for `keepAliveTime` (default 60 seconds in `ThreadPoolTaskExecutor`).
5. **Queue capacity** — how many tasks can wait before max threads are created and before tasks are rejected. A capacity of 200 means: core threads saturated → queue up to 200 tasks → spin up extra threads → reject if still overloaded.
6. **Thread name prefix** — threads will be named `blogcms-async-1`, `blogcms-async-2`, etc. This makes them immediately identifiable in thread dumps and log lines.
7. **CallerRunsPolicy** — see Decision 2 above.
8. **WaitForTasksToCompleteOnShutdown** — when the application receives a shutdown signal (SIGTERM, Ctrl+C, or deployment stop), Spring will drain the queue and wait for in-flight tasks to finish before allowing the JVM to exit.
9. **AwaitTerminationSeconds** — the maximum time to wait during shutdown. After 30 seconds, any still-running tasks are abandoned and the JVM exits. This prevents a stuck task from delaying a deployment indefinitely.
10. `initialize()` must be called explicitly when constructing `ThreadPoolTaskExecutor` outside of Spring's normal lifecycle. It creates the underlying `ThreadPoolExecutor` and prepares the thread factory. Forgetting this call causes a `NullPointerException` when the first task is submitted.

---

### `src/main/resources/application.properties` (modified)

The following block was added:

```properties
# Async / ThreadPool
async.core-pool-size=4
async.max-pool-size=16
async.queue-capacity=200
async.thread-name-prefix=blogcms-async-
```

These are the production-default values. They can be overridden per-environment via OS environment variables (Spring Boot resolves `ASYNC_CORE_POOL_SIZE` to `async.core-pool-size` automatically via relaxed binding).

**Why these numbers?**

The Blog CMS runs on port 8082. The typical Tomcat embedded server default is 200 HTTP threads. The async pool is intended for background work (email, image processing, analytics logging), not for serving HTTP responses directly. The numbers are calibrated as follows:

| Property | Value | Reasoning |
|---|---|---|
| `corePoolSize` | 4 | Baseline background capacity. Four threads can process roughly 4 concurrent background jobs without spinning up extras. Avoids thread creation overhead for normal load. |
| `maxPoolSize` | 16 | Allows burst handling up to 16 simultaneous background jobs. Beyond this, back-pressure kicks in. |
| `queueCapacity` | 200 | Absorbs traffic spikes. If 200 tasks are already queued and 16 threads are busy, the 201st task runs on the caller's thread (CallerRunsPolicy). |
| `threadNamePrefix` | `blogcms-async-` | Meaningful prefix for log correlation and thread dumps. |

---

## Concepts Introduced

### Thread Pools

A thread pool is a collection of pre-created threads that wait for work. When a task arrives, an idle thread picks it up, runs it, then returns to the pool to wait for the next task. This eliminates the overhead of creating and destroying threads for every unit of work.

The three parameters that govern pool behavior:

```
Incoming tasks
      │
      ▼
 ┌─────────────────────────────────────────────────┐
 │  Are core threads (4) all busy?                 │
 │  No  → assign to idle core thread               │
 │  Yes ↓                                          │
 │  Is queue (200 slots) not full?                 │
 │  No  → enqueue the task                         │
 │  Yes ↓                                          │
 │  Is total thread count < maxPoolSize (16)?      │
 │  No  → create a temporary extra thread          │
 │  Yes ↓                                          │
 │  Invoke RejectedExecutionHandler                │
 │  (CallerRunsPolicy: run on caller's thread)     │
 └─────────────────────────────────────────────────┘
```

### `@EnableAsync`

This annotation tells Spring to scan for `@Async`-annotated methods and wrap the beans containing them in a proxy. The proxy intercepts calls to those methods and, instead of calling them synchronously, submits the method body as a `Runnable` to the configured executor. This is built on Spring AOP — the same proxy mechanism used by `@Transactional` and `@Cacheable`.

**Important implication:** Because Spring uses a proxy, `@Async` only works when the method is called through the Spring-managed bean reference, not through a direct `this.methodName()` call within the same class. This is the self-invocation limitation (see the tutorial doc for a full explanation).

### `AsyncConfigurer` Interface

`AsyncConfigurer` is the Spring interface that lets a `@Configuration` class declare itself as the authoritative source of async infrastructure. It has two methods:

- `getAsyncExecutor()` — returns the `Executor` that `@Async` methods use by default.
- `getAsyncUncaughtExceptionHandler()` — returns a handler for exceptions thrown by `void @Async` methods (exceptions from `Future`-returning methods propagate normally through the `Future`).

### Graceful Shutdown in Spring Boot

When the application stops, Spring publishes a `ContextClosedEvent`. Beans that implement `DisposableBean` or use `@PreDestroy` run their cleanup logic. `ThreadPoolTaskExecutor` responds to this event: if `waitForTasksToCompleteOnShutdown` is `true`, it stops accepting new tasks and waits for queued and in-flight tasks to finish (up to `awaitTerminationSeconds`) before allowing the application context to fully close.

Without this, a deployment restart might interrupt an in-flight email send or image processing job mid-way through.

---

## Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Executor bean is registered | Add a `@Slf4j` log line to any `@Async` method and check thread name in logs | Log lines show `blogcms-async-1` (or similar), not `http-nio-8082-exec-*` |
| `@EnableAsync` is active | Write a trivial `@Async void` method in a test service; call it from a controller; add a 100 ms sleep inside | HTTP response returns before 100 ms elapses, proving the method ran on a background thread |
| Properties are read correctly | Add `log.info("corePoolSize={}", corePoolSize)` to `getAsyncExecutor()` and start the app | Log shows `corePoolSize=4` matching `application.properties` |
| Graceful shutdown drains tasks | Submit a slow `@Async` task (Thread.sleep 5 s), immediately stop the app with Ctrl+C | App waits up to 30 s for the task to complete before exiting; log shows task completion before JVM shutdown message |
| CallerRunsPolicy activates | Temporarily set `async.queue-capacity=0` and `async.max-pool-size=1`, then flood with async calls | Under extreme load, some tasks run on the HTTP thread (log thread name is `http-nio-8082-exec-*`); no `RejectedExecutionException` is thrown |
