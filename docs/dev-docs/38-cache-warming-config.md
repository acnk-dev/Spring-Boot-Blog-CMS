# Commit 37 — Cache Warming on Application Startup

## Overview

This commit adds a single infrastructure class, `CacheWarmingConfig`, that pre-populates the Redis cache immediately after the Spring application context finishes starting. After a server restart the Redis cache is empty. Without this class, every first request after a restart hits PostgreSQL directly, resulting in slow response times until the cache fills organically. The new class implements Spring's `ApplicationRunner` interface, which causes its `run()` method to be called once — after all beans are initialized and the application is ready to serve traffic. Inside `run()`, it calls the two read methods that carry `@Cacheable` annotations: `PostService.getAllPosts(...)` and `CategoryService.getAllCategories()`. Those methods populate Redis as a side-effect of their normal execution path, so the cache is warm from the first user request onward.

---

## Why This Was Needed

Commit 35 added `@Cacheable` annotations to four read methods in `PostServiceImpl`, and commit 36 added cache eviction on all write methods. The caching layer now works correctly during steady-state operation. However, it has a cold-start problem:

- Every time the server restarts (deploy, crash recovery, rolling update), Redis is empty.
- The first request to `GET /api/v1/posts` must query PostgreSQL, which may be slow under load.
- The first request to `GET /api/v1/categories` similarly bypasses the cache.
- Popular posts and categories are requested by many users simultaneously right after a restart (the "thundering herd" problem). Without a warm cache, all those requests fan out to PostgreSQL at once.

The fix is to proactively populate the most commonly accessed cache entries before any user request arrives. Since the `@Cacheable` methods already know how to write to Redis, this requires no new caching logic — just calling those methods at startup.

---

## Decisions Made

### 1. `ApplicationRunner` vs `CommandLineRunner`

Spring Boot provides two interfaces for running logic once after startup:

**Option A — `CommandLineRunner`**
```java
@Override
public void run(String... args) { ... }
```
Receives command-line arguments as a raw `String[]`. Works fine for this use case. Simpler signature.

**Option B — `ApplicationRunner` (chosen)**
```java
@Override
public void run(ApplicationArguments args) { ... }
```
Receives command-line arguments as an `ApplicationArguments` object, which parses option names (`--foo=bar`) and non-option values separately.

*Chosen because:* `ApplicationRunner` is slightly more extensible if the warming logic ever needs to inspect startup flags (e.g., `--skip-cache-warm` for integration tests). The functional difference for this commit is zero — no arguments are read. The project uses `ApplicationRunner` as the idiom in `DataSeeder.java`, so this maintains consistency.

---

### 2. How many pages / which methods to warm

The `PostService` interface has several read methods: `getAllPosts`, `getPostById`, `getPostBySlug`, `getPostsByUser`, `getPostsByCategory`, `getPostsByTag`, `searchPosts`. Not all of them are cached (see commit 35 — only `getPostById`, `getPostBySlug`, `getAllPosts`, and `getPostsByCategory` have `@Cacheable`).

**Option A — Warm every cached method exhaustively**
Call `getPostById` for every post ID, `getPostBySlug` for every slug, `getPostsByCategory` for every category ID. This would fully populate Redis.

*Rejected because:* This requires knowing all post IDs and all category IDs at startup, which means extra repository queries. It also risks making startup slow if there are thousands of posts. The benefit (every single-entity key is warm) is minor — individual post lookups by ID are fast even from PostgreSQL. The high-value entries are the listing pages (fetched by many users simultaneously), not individual entity lookups.

**Option B — Warm only the first listing page and all categories (chosen)**
Call `getAllPosts(0, 20, "createdAt", "desc")` and `getAllCategories()`. These two calls cover the most common entry points: the homepage post listing and the navigation category menu.

*Chosen because:* These are the two queries that every user triggers on first load. Warming them eliminates the thundering-herd problem for the most impactful endpoints without adding repository complexity or slowing startup.

---

### 3. Failure isolation per call vs single try/catch

**Option A — Single try/catch around all warming calls**
```java
try {
    postService.getAllPosts(...);
    categoryService.getAllCategories();
} catch (Exception ex) {
    log.warn("Cache warming failed — {}", ex.getMessage());
}
```
*Rejected because:* A failure in the first call (e.g., Redis is temporarily unavailable and `getAllPosts` throws) would silently skip `getAllCategories`. The categories cache would remain cold even though the posts cache may have partially succeeded.

**Option B — Separate try/catch per call (chosen)**
Each warming call has its own try/catch block. A failure in one does not prevent the others from running.

*Chosen because:* This is the safer pattern. It also produces a more useful log message that identifies exactly which warming call failed. The implementation is extracted into private helper methods (`warmPostsCache`, `warmCategoriesCache`) to keep `run()` readable.

---

### 4. No new service methods

The task explicitly required using only existing service methods. `PostService` has no `getPopularPosts()` method. Rather than add one to satisfy a nice-to-have warming scenario, the class uses only `getAllPosts` and `getAllCategories`, which already exist and are already cached.

---

## New Concepts Introduced

### `ApplicationRunner` — what it is and how it works

`ApplicationRunner` is a Spring Boot interface in the `org.springframework.boot` package. Any `@Component` (or `@Bean`) that implements it will have its `run(ApplicationArguments args)` method called by Spring Boot once the application context is fully initialized and the embedded web server is ready to accept requests.

The sequence is:
```
1. Spring creates all beans (DI wiring, @PostConstruct calls)
2. Embedded Tomcat starts and binds to the configured port
3. Spring Boot calls ApplicationRunner.run() for every registered runner
4. Application is ready to serve HTTP traffic
```

The key property is step 3: `run()` executes *after* the server is ready, not *during* bean creation. This means:
- It is safe to call service methods (all dependencies are wired)
- It is safe to call repository methods (JPA is initialized)
- It is safe to call Redis (the connection pool is established)
- A failure in `run()` does NOT prevent the application from starting if the exception is caught — only an uncaught exception would cause startup to abort

Multiple `ApplicationRunner` beans can coexist. Spring calls them in order of `@Order` annotation (or `Ordered` interface). Since `CacheWarmingConfig` has no `@Order`, it uses the default order and runs after `DataSeeder` (which also has no `@Order` — order between them is not guaranteed and does not matter because they have no dependency on each other).

### Why calling `@Cacheable` methods from `ApplicationRunner` populates Redis

The `@Cacheable` annotation works by wrapping the service bean in a proxy (using Spring AOP). When any caller invokes `postService.getAllPosts(0, 20, "createdAt", "desc")`, the proxy intercepts the call:

```
CacheWarmingConfig.run()
    │
    ▼
CachingProxy (wraps PostServiceImpl)
    │
    ├── 1. Check Redis for key "posts::all_0_20_createdAt_desc"
    │
    ├── 2a. Cache HIT  → return cached Page<PostResponse>
    │
    └── 2b. Cache MISS → call real PostServiceImpl.getAllPosts(...)
                          → query PostgreSQL
                          → store result in Redis under key "posts::all_0_20_createdAt_desc"
                          → return Page<PostResponse>
```

`CacheWarmingConfig` injects the `PostService` interface, which Spring resolves to the proxy-wrapped `PostServiceImpl`. This is why injecting the interface (not the impl) is essential: injecting the impl directly would bypass the AOP proxy and the `@Cacheable` annotation would not fire.

---

## Files Changed

### `src/main/java/com/studylink/blogcms/config/CacheWarmingConfig.java` (new)

This is a new file. Key decisions visible in the code:

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class CacheWarmingConfig implements ApplicationRunner {

    // Interfaces injected — NOT PostServiceImpl or CategoryServiceImpl.
    // Injecting the interface ensures the AOP proxy is injected, which means
    // @Cacheable on the impl's methods will actually fire.
    private final PostService postService;
    private final CategoryService categoryService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Cache warming started — pre-loading popular data into Redis");

        warmPostsCache();
        warmCategoriesCache();

        log.info("Cache warming completed");
    }

    private void warmPostsCache() {
        try {
            // Exact signature: getAllPosts(int page, int size, String sortBy, String sortDir)
            // Using the same defaults as the PostController's list endpoint.
            postService.getAllPosts(0, 20, "createdAt", "desc");
            log.info("Cache warming: first page of posts loaded (page=0, size=20)");
        } catch (Exception ex) {
            // Warn, not error — a cache miss on startup is not an application error.
            // A Redis outage or empty database should not prevent startup.
            log.warn("Cache warming: failed to pre-load posts — {}", ex.getMessage());
        }
    }

    private void warmCategoriesCache() {
        try {
            categoryService.getAllCategories();
            log.info("Cache warming: all categories loaded");
        } catch (Exception ex) {
            log.warn("Cache warming: failed to pre-load categories — {}", ex.getMessage());
        }
    }
}
```

---

## Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Compilation is clean | `./mvnw compile -q` | Exit 0, no output |
| Warming runs at startup | Start the app; check logs | Log lines: "Cache warming started", "first page of posts loaded", "all categories loaded", "Cache warming completed" |
| Redis is populated after startup | Start app; run `redis-cli KEYS "posts::*"` | At least one key matching `posts::all_*` is present |
| Categories are cached | Start app; run `redis-cli KEYS "categories::*"` | At least one categories key is present |
| First HTTP request is a cache hit | Start app; `GET /api/v1/posts?page=0&size=20&sortBy=createdAt&sortDir=desc`; check logs | No Hibernate SQL logged for this request (served from cache) |
| First categories request is a cache hit | Start app; `GET /api/v1/categories`; check logs | No Hibernate SQL logged (served from cache) |
| Redis outage does not crash startup | Stop Redis; start the app | App starts successfully; logs show two `WARN` lines about warming failures; app serves traffic (cache misses hit DB) |
| Empty database does not crash startup | Point to an empty DB (no posts, no categories); start the app | App starts successfully; warming calls return empty results; no exception propagates |
| Warming does not block request serving | Start app; send requests immediately after the "Started BlogCmsApplication" log line | Requests are handled — warming completes in the background before or shortly after |
