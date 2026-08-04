# Commit 44 — Scheduled Cleanup Jobs for Soft-Deleted Records

## 1. Overview

This commit introduces four scheduled background jobs that run automatically on a cron schedule. Three of the jobs do real work — purging posts that have been soft-deleted for more than 30 days, purging users in the same state, and logging database health statistics. A fourth job slot (`purge-tokens`) was defined in `application.properties` for future use but is not wired to any code because the `RefreshToken` entity does not exist in this project.

The new classes are `CleanupService` (interface) and `CleanupServiceImpl` (implementation), following the same layered pattern used everywhere else in this project. The scheduling machinery is enabled by adding `@EnableScheduling` and a dedicated `ThreadPoolTaskScheduler` bean to the existing `AsyncConfig.java`.

---

## 2. Why This Was Needed

### The soft-delete accumulation problem

This project uses soft deletes: when a user or post is "deleted", the record is not removed from the database — instead, an `isDeleted` flag is set to `true`. This is intentional and desirable: it gives operators a grace period to recover accidentally deleted content, supports audit trails, and avoids foreign-key cascade headaches.

The downside is that soft-deleted records accumulate forever unless something removes them eventually. In a production system with active content moderation this can mean tens of thousands of orphaned rows that consume disk space, slow down table scans, and inflate backup sizes.

### Why not hard-delete immediately?

If a user clicks "Delete Post" and the post is removed from the database in the same request, there is no recovery window. A 30-day retention window means:
- An admin can restore accidentally deleted content for 30 days.
- GDPR "right to erasure" requests are still honoured eventually (the record does leave the system).
- The retention period is configurable without code changes.

### Why scheduled cleanup rather than on-demand?

On-demand cleanup (e.g., a REST endpoint that purges old records) has two problems:
1. It requires someone to call it. Operations teams forget.
2. It ties a potentially slow bulk-delete operation to an HTTP request, which risks timeouts.

A cron job runs automatically in the background at a quiet time (02:00 AM), decoupled from any HTTP request lifecycle.

---

## 3. Decisions Made

### 3a. `@Scheduled` cron vs `fixedRate` vs `fixedDelay`

Spring's `@Scheduled` annotation supports three timing modes:

| Mode | Meaning | Example |
|---|---|---|
| `fixedRate` | Fire every N milliseconds from the *start* of the last invocation | `@Scheduled(fixedRate = 60000)` — every 60 s |
| `fixedDelay` | Fire N milliseconds after the *end* of the last invocation | `@Scheduled(fixedDelay = 60000)` — 60 s after previous finishes |
| `cron` | Fire at a calendar time described by a 6-field expression | `@Scheduled(cron = "0 0 2 * * *")` — 02:00 AM daily |

**fixedRate rejected because:** cleanup jobs should fire at a predictable calendar time (2 AM), not "every N seconds from startup". fixedRate also allows overlap if a job takes longer than the interval.

**fixedDelay rejected because:** it still doesn't give you "run at 2 AM". It also means the schedule drifts based on how long the job took.

**Cron chosen because:** it fires at an exact wall-clock time, which is what operations teams care about when scheduling maintenance windows. It is also the industry-standard way to express recurring jobs.

### 3b. Separate `ThreadPoolTaskScheduler` vs sharing the `@Async` executor

The existing `TaskExecutor` bean is used by `@Async` methods (e.g., sending emails when a post is published). If we registered a `TaskScheduler` with the same pool:

- A slow cleanup job doing a large bulk delete could occupy all threads.
- Meanwhile, email notifications triggered by user actions would queue up or be rejected.
- This would silently degrade user-facing functionality during the nightly cleanup window.

**Chosen approach:** a separate `ThreadPoolTaskScheduler` bean named `scheduledExecutor` with pool size 4 and thread prefix `blogcms-scheduler-`. The two pools are completely independent. The async pool never waits for the scheduler pool and vice versa.

### 3c. Retention window: 30 days

**0 days (immediate hard-delete) rejected because:** no recovery window, breaks the purpose of soft deletes.

**90+ days rejected because:** excessive disk growth for typical blog content volumes.

**30 days chosen because:** it is a common industry default (aligns with many GDPR implementations, gives a reasonable recovery window, and is configurable via `cleanup.deleted-retention-days` without code changes).

### 3d. JPQL DELETE vs load-then-delete

```java
// Option A — load entities, then delete one-by-one (REJECTED)
List<Post> toDelete = postRepository.findByIsDeletedTrueAndUpdatedAtBefore(cutoff);
toDelete.forEach(postRepository::delete);

// Option B — single JPQL DELETE (CHOSEN)
int deleted = postRepository.hardDeleteByIsDeletedTrueAndUpdatedAtBefore(cutoff);
```

Loading entities into memory just to delete them is wasteful — it generates N+1 SELECT statements before the DELETE. The `@Modifying @Query("DELETE FROM ...")` approach translates to a single `DELETE FROM posts WHERE ...` SQL statement regardless of how many rows match. This is the same principle already used for `incrementViewCount` and `incrementLikeCount` in `PostRepository`.

The `findByIsDeletedTrueAndUpdatedAtBefore` method was also added (as required by the task spec) and is available for callers that genuinely need to inspect the records before deletion — but `CleanupServiceImpl` uses the direct DELETE.

---

## 4. New Concepts Introduced

### `@EnableScheduling`

This annotation, placed on a `@Configuration` class, tells Spring to activate its scheduling infrastructure. Without it, `@Scheduled` annotations are silently ignored — the methods are never invoked.

Internally, `@EnableScheduling` imports `SchedulingConfiguration`, which registers a `ScheduledAnnotationBeanPostProcessor`. This post-processor scans every Spring-managed bean at startup, finds methods annotated with `@Scheduled`, and registers them with a `TaskScheduler` to be invoked at the right time.

In this project it is placed on `AsyncConfig` alongside `@EnableAsync` — both are scheduling-related concerns and there is no value in splitting them across two config files.

### `@Scheduled(cron = "...")`

A method-level annotation that marks a method to be called by the Spring task scheduler. The method must:
- Return `void`
- Accept no arguments
- Be on a Spring-managed bean (i.e., `@Service`, `@Component`, etc.)

The `cron` attribute accepts a 6-field Spring cron expression (see the cron cheat sheet below).

### Spring Cron Expression Syntax

Spring uses a **6-field** cron format (unlike Unix cron which has 5):

```
┌─────────── second        (0–59)
│ ┌───────── minute        (0–59)
│ │ ┌─────── hour          (0–23)
│ │ │ ┌───── day-of-month  (1–31)
│ │ │ │ ┌─── month         (1–12 or JAN–DEC)
│ │ │ │ │ ┌─ day-of-week   (0–7 or SUN–SAT, 0 and 7 are both Sunday)
│ │ │ │ │ │
* * * * * *
```

Common special characters:

| Character | Meaning | Example |
|---|---|---|
| `*` | Every value | `*` in hour = every hour |
| `*/N` | Every N units | `*/6` in hour = every 6 hours |
| `N` | Exact value | `0` in second = at second 0 |
| `,` | List | `1,15` in day = 1st and 15th |
| `-` | Range | `9-17` in hour = 9 AM to 5 PM |

Expressions used in this commit:

| Expression | Fires |
|---|---|
| `0 0 2 * * *` | Every day at 02:00:00 |
| `0 30 2 * * *` | Every day at 02:30:00 |
| `0 0 * * * *` | Every hour at :00:00 |
| `0 0 */6 * * *` | Every 6 hours at :00:00 (00:00, 06:00, 12:00, 18:00) |

### `ThreadPoolTaskScheduler`

Spring's implementation of `TaskScheduler` that maintains a pool of threads dedicated to running scheduled tasks. Key properties:

- `setPoolSize(int)` — how many threads can run jobs concurrently. 4 is enough here because our four jobs are short database operations and run at different times.
- `setThreadNamePrefix(String)` — makes threads identifiable in thread dumps (e.g., `blogcms-scheduler-1`).
- `setWaitForTasksToCompleteOnShutdown(true)` + `setAwaitTerminationSeconds(30)` — on application shutdown, Spring waits up to 30 seconds for in-flight jobs to finish rather than interrupting them mid-delete.

Contrast with `ThreadPoolTaskExecutor` (used for `@Async`): the executor is optimised for ad-hoc task submission (user triggers an email → submit task). The scheduler is optimised for time-triggered tasks.

### `@Transactional` on `@Scheduled` methods

`@Modifying` JPQL DELETE queries require a transaction. Without `@Transactional`, Spring throws `TransactionRequiredException` at runtime the first time a job fires.

The `readOnly = true` class-level default is not applied to `CleanupServiceImpl` — each method declares its own `@Transactional` so the intent is explicit:
- Write jobs (`purgeDeletedPosts`, `purgeDeletedUsers`): `@Transactional` (read-write)
- Stats job (`logDatabaseStats`): `@Transactional(readOnly = true)` (no writes, hint to JDBC driver)

---

## 5. Files Changed

### `src/main/java/com/studylink/blogcms/config/AsyncConfig.java`
**What changed:** Added `@EnableScheduling` annotation and a new `scheduledExecutor` `@Bean`.

Key addition:
```java
@Configuration
@EnableAsync
@EnableScheduling          // NEW — activates @Scheduled processing
public class AsyncConfig implements AsyncConfigurer {

    // ... existing taskExecutor bean unchanged ...

    @Bean(name = "scheduledExecutor")
    public ThreadPoolTaskScheduler scheduledExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("blogcms-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }
}
```

### `src/main/resources/application.properties`
**What changed:** Appended five cleanup-related properties.

```properties
# Scheduled Cleanup Jobs
cleanup.deleted-retention-days=30
cleanup.cron.purge-posts=0 0 2 * * *
cleanup.cron.purge-users=0 30 2 * * *
cleanup.cron.purge-tokens=0 0 * * * *
cleanup.cron.stats=0 0 */6 * * *
```

`purge-tokens` is defined for future use when a `RefreshToken` entity is added. It is not referenced by any code today.

### `src/main/java/com/studylink/blogcms/repository/PostRepository.java`
**What changed:** Added three methods for cleanup operations.

```java
// Find candidates (useful for inspection before deletion)
List<Post> findByIsDeletedTrueAndUpdatedAtBefore(LocalDateTime cutoff);

// Bulk hard-delete via single SQL statement
@Modifying
@Query("DELETE FROM Post p WHERE p.isDeleted = true AND p.updatedAt < :cutoff")
int hardDeleteByIsDeletedTrueAndUpdatedAtBefore(@Param("cutoff") LocalDateTime cutoff);

// Count for stats job
long countByIsDeletedTrue();
```

### `src/main/java/com/studylink/blogcms/repository/UserRepository.java`
**What changed:** Same three methods, mirrored for the `User` entity.

### `src/main/java/com/studylink/blogcms/service/CleanupService.java` (new)
**What it is:** Service interface declaring the three cleanup operations. Follows the project convention of always having an interface even for singleton implementations.

### `src/main/java/com/studylink/blogcms/service/impl/CleanupServiceImpl.java` (new)
**What it is:** Implementation of `CleanupService`. Key structural points:

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class CleanupServiceImpl implements CleanupService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;

    @Value("${cleanup.deleted-retention-days:30}")
    private int retentionDays;

    @Override
    @Scheduled(cron = "${cleanup.cron.purge-posts}")   // reads from properties
    @Transactional
    public void purgeDeletedPosts() {
        long start = System.currentTimeMillis();
        log.info("[CleanupJob] purgeDeletedPosts starting — retention window: {} days", retentionDays);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int deleted = postRepository.hardDeleteByIsDeletedTrueAndUpdatedAtBefore(cutoff);

        log.info("[CleanupJob] purgeDeletedPosts complete — {} post(s) hard-deleted in {}ms",
                 deleted, System.currentTimeMillis() - start);
    }

    // purgeDeletedUsers follows the identical pattern for the User entity

    @Override
    @Scheduled(cron = "${cleanup.cron.stats}")
    @Transactional(readOnly = true)
    public void logDatabaseStats() {
        long start = System.currentTimeMillis();
        long softDeletedPosts = postRepository.countByIsDeletedTrue();
        long softDeletedUsers = userRepository.countByIsDeletedTrue();
        log.info("[CleanupJob] logDatabaseStats — soft-deleted posts: {}, users: {} ({}ms)",
                 softDeletedPosts, softDeletedUsers, System.currentTimeMillis() - start);
    }
}
```

### What the stats job actually does

`logDatabaseStats` runs every 6 hours (`0 0 */6 * * *`) and produces a single log line like:

```
[CleanupJob] logDatabaseStats — soft-deleted posts: 4, users: 4 (12ms)
```

It makes **no writes** — only two `COUNT(*)` queries. This is a purely observability job.

**Why it matters:**

- **Drift detection** — if the soft-deleted count keeps climbing between cleanup runs, it signals the purge jobs may have silently failed or stopped firing
- **Purge verification** — after the 2 AM purge runs, the next stats tick should show a lower count, confirming the cleanup worked
- **Zero cost** — two `COUNT(*)` queries every 6 hours is negligible database load

**In production** you would replace the `log.info` with a metrics call (e.g. Prometheus `Gauge`, Datadog `gauge`, or Micrometer) so you can graph the count over time and alert when it exceeds a threshold. For this teaching project, logging is sufficient and keeps the dependency footprint small.

---

## 6. Cron Expression Cheat Sheet

| Expression | Plain English |
|---|---|
| `0 0 * * * *` | Every hour on the hour |
| `0 */30 * * * *` | Every 30 minutes |
| `0 0 2 * * *` | Every day at 2:00 AM |
| `0 0 2 * * MON` | Every Monday at 2:00 AM |
| `0 0 0 1 * *` | First day of every month at midnight |
| `0 0 */6 * * *` | Every 6 hours (00:00, 06:00, 12:00, 18:00) |
| `0 0 9-17 * * MON-FRI` | Every hour between 9 AM and 5 PM on weekdays |
| `*/10 * * * * *` | Every 10 seconds (useful for testing!) |

---

## 7. Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Jobs fire on schedule | Temporarily change `cleanup.cron.purge-posts` to `*/10 * * * * *` and restart the app | Log line `[CleanupJob] purgeDeletedPosts starting` appears every 10 seconds |
| purgeDeletedPosts deletes correctly | Soft-delete a post, set `cleanup.deleted-retention-days=0`, trigger job, check DB | Post row is gone from `posts` table; no SQL error |
| purgeDeletedUsers deletes correctly | Same pattern for a user | User row is gone from `users` table |
| logDatabaseStats fires | Set `cleanup.cron.stats` to `*/15 * * * * *`, wait 15 s | Log line shows current counts of soft-deleted records |
| Retention window respects retentionDays | Soft-delete a record, do NOT set retention to 0 | Record is NOT deleted (it was just soft-deleted, updatedAt is now, cutoff is 30 days ago) |
| Separate thread pool | Watch log output during job execution | Thread names show `blogcms-scheduler-N`, not `blogcms-async-N` |
| Stats job is read-only | Enable SQL logging, trigger stats job | Only SELECT COUNT statements appear, no INSERT/UPDATE/DELETE |
| Graceful shutdown | Start app, trigger a job, CTRL+C immediately | App waits up to 30 s for the job to finish before exiting |
