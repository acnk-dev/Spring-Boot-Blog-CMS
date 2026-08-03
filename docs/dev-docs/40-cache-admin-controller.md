# Commit 39 — Cache Admin Controller

## 1. Overview

This commit adds `CacheAdminController`, a Spring `@RestController` that gives administrators live visibility into the Redis cache and the ability to evict entries without restarting the server. The controller exposes four endpoints under `/api/v1/admin/cache`: a stats view (entry counts per cache), a single-cache clear, a full clear-all, and a raw key listing for a named cache. Every endpoint is locked behind `@PreAuthorize("hasRole('ADMIN')")` at the class level, so a single annotation guards all four methods simultaneously.

No service layer was introduced. `CacheManager` and `StringRedisTemplate` are infrastructure beans provided by Spring Boot's auto-configuration; routing them through a service interface would add boilerplate without adding any business logic value.

---

## 2. Why this was needed

Once Redis caching was activated on `PostServiceImpl` (commits 35–38), operators needed a way to:

1. **Observe** how many entries are currently live in each cache namespace — useful for confirming that warming worked, that TTLs are being respected, and that cache keys are formatted as expected.
2. **Manually evict** stale entries without waiting for TTL expiry — for example, after a bulk data correction that bypasses the application.
3. **Inspect raw key names** — the exact key string tells you what parameters were used to populate each entry (post ID, pagination params, etc.).

Without these endpoints the only alternative was shelling into the Redis server and running `KEYS *` directly — inconvenient and unavailable in most cloud environments.

---

## 3. Decisions made

### 3a. Class-level vs method-level `@PreAuthorize`

**Option A — method-level `@PreAuthorize` on each handler.** Used by `PostController`, `CategoryController`, and `TagController` where individual methods have different permission requirements (some are public, some require AUTHOR, some ADMIN).

**Option B (chosen) — class-level `@PreAuthorize("hasRole('ADMIN')")`**. Every endpoint in this controller is admin-only. Putting the annotation on the class is less repetitive and makes the intent unmissable: if a developer adds a new method, it is automatically protected unless they explicitly override it on the method.

### 3b. Role string format: `hasRole('ADMIN')` vs `hasAuthority('ROLE_ADMIN')`

Spring Security's `hasRole('ADMIN')` automatically prepends `ROLE_` when checking the user's granted authorities. The existing controllers in this project (e.g. `PostController`) use `hasAnyRole('AUTHOR', 'ADMIN')` and SecurityConfig uses `hasRole("ADMIN")`. This controller follows the same convention: `hasRole('ADMIN')`.

### 3c. No service layer for cache operations

**Option A — introduce `CacheAdminService` interface + impl.** Follows the standard layered architecture pattern used by `PostService`, `CategoryService`, etc.

**Option B (chosen) — call `CacheManager` and `StringRedisTemplate` directly from the controller.** The CLAUDE.md rule "business logic belongs in ServiceImpl" exists to separate concerns where the _domain logic_ is non-trivial. Cache introspection is pure infrastructure: the controller receives a request, queries a Spring-managed bean, and returns a result. There is no domain model, no ownership rule, no transaction, and no exception translation needed. Adding a service interface here would be mechanical ceremony with no teaching or maintenance benefit.

### 3d. `org.springframework.cache.Cache` interface vs casting to `RedisCache`

The task specification mentioned casting to `RedisCache`. In practice, calling `cache.clear()` via the `org.springframework.cache.Cache` interface (Spring's abstraction) is cleaner and less coupled to the Redis implementation. The `clear()` method is defined on the interface and delegates to `RedisCacheWriter` internally. Casting to `RedisCache` is only necessary when accessing Redis-specific configuration (like TTL settings), which none of these endpoints need. The interface approach was chosen.

### 3e. `KEYS` command vs `SCAN` for key listing

`StringRedisTemplate.keys(pattern)` executes Redis's `KEYS` command, which is O(N) and blocks the Redis event loop for the entire scan. On large keyspaces this causes latency spikes.

**Option A (chosen for this project) — `KEYS`**. Simpler code, acceptable for a development/teaching environment where the keyspace is small and the endpoint is admin-only (not called frequently).

**Option B — `SCAN`-based iteration** using `StringRedisTemplate.scan(ScanOptions)`. Non-blocking, safe for production. Recommended for any deployment where the Redis keyspace is large. A comment in the controller explains this trade-off explicitly so a future developer knows what to change.

---

## 4. New concepts introduced

### 4a. Class-level `@PreAuthorize`

`@PreAuthorize` is a Spring Security annotation that evaluates a Spring Expression Language (SpEL) expression before the annotated method is called. It is normally placed on individual methods. When placed on the **class**, Spring applies it as a default to every method in the class — the effect is identical to copying the annotation onto each method individually. A method-level `@PreAuthorize` on an individual handler overrides the class-level one for that specific method.

```java
@PreAuthorize("hasRole('ADMIN')")   // applies to ALL methods below
public class CacheAdminController {

    @GetMapping("/stats")
    // implicitly requires ROLE_ADMIN — no annotation needed here
    public ResponseEntity<...> getCacheStats() { ... }
}
```

This works because `@EnableMethodSecurity(prePostEnabled = true)` is already declared in `SecurityConfig`, which tells Spring to process `@PreAuthorize` annotations on beans.

### 4b. `CacheManager` — Spring's cache abstraction

`CacheManager` is Spring's abstraction over any caching backend (Redis, Caffeine, EhCache, etc.). It provides two operations:

- `getCacheNames()` — returns the collection of named caches registered at startup.
- `getCache(name)` — returns a `Cache` object for that name, or `null` if the name is unknown.

The `Cache` object (interface `org.springframework.cache.Cache`) wraps the underlying store and exposes methods like `get(key)`, `put(key, value)`, and `clear()`. By programming to `CacheManager` and `Cache` (rather than `RedisCacheManager` and `RedisCache`), the controller remains cache-backend-agnostic — it would work equally well with Caffeine in tests.

In this project, `RedisCacheConfig.cacheManager()` (commit 34) produces the concrete `RedisCacheManager` bean; Spring injects it wherever `CacheManager` is declared.

### 4c. `StringRedisTemplate`

`StringRedisTemplate` is a specialization of `RedisTemplate<String, String>` pre-configured with `StringRedisSerializer` for both keys and values. It provides direct access to Redis commands through operation views:

```
stringRedisTemplate.keys("posts::*")        // KEYS command
stringRedisTemplate.opsForValue().get(key)  // GET command
stringRedisTemplate.delete(key)             // DEL command
```

It is used here specifically to call `keys(pattern)`, which executes the Redis `KEYS` command and returns the matching key strings. Spring Boot auto-configures a `StringRedisTemplate` bean whenever `spring-boot-starter-data-redis` is on the classpath and a Redis connection is configured, so no additional setup is needed.

### 4d. `LinkedHashMap` for ordered JSON output

`HashMap` does not guarantee iteration order, so response keys appear in an unpredictable sequence in the JSON output. `LinkedHashMap` preserves insertion order. For the stats endpoint this means the cache names always appear in the order they were iterated from `getCacheNames()` — a small usability improvement that makes repeated calls easier to compare visually.

---

## 5. Files changed

### `src/main/java/com/studylink/blogcms/controller/CacheAdminController.java` (new)

New controller with four endpoints. Key excerpt showing class-level security and the stats endpoint:

```java
@RestController
@RequestMapping("/api/v1/admin/cache")
@PreAuthorize("hasRole('ADMIN')")          // guards every method in this class
@RequiredArgsConstructor
@Slf4j
public class CacheAdminController {

    private final CacheManager cacheManager;
    private final StringRedisTemplate stringRedisTemplate;

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        Map<String, Object> cacheStats = new LinkedHashMap<>();

        for (String cacheName : cacheManager.getCacheNames()) {
            Set<String> keys = stringRedisTemplate.keys(cacheName + "::*");
            int entryCount = (keys != null) ? keys.size() : 0;

            Map<String, Object> cacheInfo = new LinkedHashMap<>();
            cacheInfo.put("entryCount", entryCount);
            cacheStats.put(cacheName, cacheInfo);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("caches", cacheStats);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/clear/{cacheName}")
    public ResponseEntity<Map<String, String>> clearCache(@PathVariable String cacheName) {
        org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("message", "Cache '" + cacheName + "' not found. Known caches: "
                    + cacheManager.getCacheNames());
            return ResponseEntity.status(404).body(errorResponse);
        }
        cache.clear();
        Map<String, String> response = new HashMap<>();
        response.put("message", "Cache '" + cacheName + "' cleared successfully");
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/clear-all")
    public ResponseEntity<Map<String, String>> clearAllCaches() {
        int count = 0;
        for (String cacheName : cacheManager.getCacheNames()) {
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) { cache.clear(); count++; }
        }
        Map<String, String> response = new HashMap<>();
        response.put("message", count + " cache(s) cleared successfully");
        response.put("clearedCaches", String.join(", ", cacheManager.getCacheNames()));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/keys/{cacheName}")
    public ResponseEntity<Set<String>> getCacheKeys(@PathVariable String cacheName) {
        org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return ResponseEntity.status(404).build();
        }
        Set<String> keys = stringRedisTemplate.keys(cacheName + "::*");
        if (keys == null) { keys = Set.of(); }
        return ResponseEntity.ok(keys);
    }
}
```

---

## 6. Testing checklist

First obtain an ADMIN JWT:

```bash
curl -s -X POST http://localhost:8082/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"password"}' \
  | jq '.token'
```

Set `TOKEN=<value>` from the response, then run:

| What to test | How to test | Expected result |
|---|---|---|
| Stats endpoint returns cache names and counts | `GET /api/v1/admin/cache/stats` with ADMIN token | 200; JSON with `caches.posts.entryCount`, `caches.categories.entryCount`, `caches.tags.entryCount` |
| Stats requires authentication | `GET /api/v1/admin/cache/stats` with no token | 403 Forbidden |
| Stats rejects non-admin users | `GET /api/v1/admin/cache/stats` with AUTHOR token | 403 Forbidden |
| Entry count increases after a GET post request | Call `GET /api/v1/posts/1`, then `/stats` | `posts.entryCount` is at least 1 |
| Clear single cache | `DELETE /api/v1/admin/cache/clear/posts` with ADMIN token | 200; `{"message":"Cache 'posts' cleared successfully"}` |
| Entry count drops to 0 after clear | `/stats` immediately after clear | `posts.entryCount` = 0 |
| Clear unknown cache returns 404 | `DELETE /api/v1/admin/cache/clear/nonexistent` | 404; message includes "not found" and lists known caches |
| Clear all caches | `DELETE /api/v1/admin/cache/clear-all` with ADMIN token | 200; `message` contains "cache(s) cleared successfully"; `clearedCaches` lists all names |
| Key listing for known cache | `GET /api/v1/admin/cache/keys/posts` | 200; JSON array of key strings like `["posts::1","posts::SimpleKey [...]"]` |
| Key listing for unknown cache returns 404 | `GET /api/v1/admin/cache/keys/bogus` | 404 |
| Key listing returns empty array when cache is empty | Clear posts cache, then `/keys/posts` | 200; `[]` |
