# Commit 45 — Distributed Rate Limiting with Bucket4j and Redis

## 1. Overview

This commit adds distributed HTTP rate limiting to the Blog CMS using the **Bucket4j** library backed by **Redis**. Every API request now passes through a new `RateLimitFilter` that checks whether the caller has exceeded their allowed request quota. If they have, the filter immediately responds with HTTP 429 (Too Many Requests) and never lets the request reach a controller. Limits are enforced per authenticated user (keyed by username and role) and per IP address for anonymous callers.

"Distributed" means the rate-limit counters live in Redis, not in the JVM's heap. This matters when you run more than one instance of the application behind a load balancer: without a shared store, each pod would have its own counter and a user could send five times their quota by cycling across five pods.

---

## 2. Why this was needed

Without rate limiting, any caller — authenticated or anonymous — can hammer any endpoint indefinitely. This creates two problems:

1. **Abuse / denial of service** — a single bad actor can exhaust database connections, CPU, or Redis bandwidth for everyone else.
2. **Fairness** — free SUBSCRIBER accounts should get less headroom than paid AUTHOR accounts.

The request was to implement the standard industry approach: token-bucket rate limiting with per-role thresholds, enforced across all pods via Redis.

---

## 3. Decisions made

### 3.1 Algorithm: token bucket vs. fixed window vs. sliding window

- **Fixed window** (e.g., "100 requests per hour, counter resets at :00") — simple but allows a burst of 200 requests straddling the window boundary (100 at :59 and 100 at :01 of the next hour). *Rejected because* the boundary burst is a well-known weakness.
- **Sliding window** — accurate but requires storing a timestamp for every request, which gets expensive at high throughput. *Rejected because* complexity is not justified here.
- **Token bucket (chosen)** — a bucket starts full (e.g. 100 tokens). Each request consumes 1 token. Tokens refill continuously over time up to the bucket's capacity. No sudden boundary resets, no per-request timestamp storage. This is the model used by AWS API Gateway, Stripe, and GitHub's REST API. Bucket4j implements it natively.

### 3.2 State store: Redis vs. in-memory (Caffeine)

- **In-memory / Caffeine** — zero extra infrastructure, sub-microsecond access. *Rejected because* counters are local to one JVM. A user can bypass the limit by hitting different pods.
- **Redis (chosen)** — already in the project for caching (commit 34). Bucket4j's `LettuceBasedProxyManager` implements atomic compare-and-swap (CAS) via Redis Lua scripts, so counter updates across concurrent requests are safe without application-level locking.

### 3.3 Where to enforce: Filter vs. Spring AOP interceptor vs. controller annotation

- **AOP interceptor / custom annotation on controller methods** — more granular (per-endpoint limits). *Rejected because* it requires annotating every method and doesn't protect unauthenticated endpoints where the principal is unknown.
- **Spring Security DSL (request matchers)** — Security's built-in request matchers don't have a rate-limit hook. *Not applicable.*
- **OncePerRequestFilter (chosen)** — runs in the Servlet filter chain before any Spring MVC processing. A single class protects every endpoint uniformly. Follows the same pattern as `JwtAuthenticationFilter`.

### 3.4 Filter ordering: before or after JwtAuthenticationFilter?

The rate-limit filter must run **after** `JwtAuthenticationFilter` because it reads `SecurityContextHolder` to determine the authenticated user's role. If it ran before, the security context would be empty and every request would fall back to the anonymous (SUBSCRIBER) limit.

Registered with `addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class)` in `SecurityConfig`.

### 3.5 Key type: `String` vs. `byte[]`

`LettuceBasedProxyManager.builderFor(RedisClient)` — the most convenient overload — produces a `ProxyManager<byte[]>` because the Redis wire protocol works with raw bytes. There is a `builderFor(StatefulRedisConnection<String, byte[]>)` overload that would give a `ProxyManager<String>`, but it requires manually opening and lifecycle-managing a Lettuce connection.

**Chosen:** use the `RedisClient` overload (simpler) and convert human-readable String keys to UTF-8 bytes at the call site in the filter. This is a one-liner (`key.getBytes(StandardCharsets.UTF_8)`) and has no functional downside.

### 3.6 Redis key design

```
rate_limit:<username>:<role>    ← authenticated
rate_limit:anon:<ip>            ← unauthenticated
```

The role is included in the key so that if a user's role changes (e.g., SUBSCRIBER upgraded to AUTHOR), they automatically get a fresh bucket with the new limit rather than inheriting the old bucket's remaining tokens.

---

## 4. New concepts introduced

### 4.1 Token bucket algorithm

Imagine a physical bucket that holds N marbles (tokens). When a request arrives:

1. Try to remove 1 marble — `bucket.tryConsume(1)`.
2. If there is a marble, remove it and allow the request.
3. If the bucket is empty, reject the request (HTTP 429).

Meanwhile, marbles are added back at a fixed rate (the "refill rate") up to the bucket's capacity. With Bucket4j's **greedy refill**, tokens are added continuously rather than in batches at the end of the window. This prevents the burst attack described in section 3.1.

```java
Bandwidth.builder()
    .capacity(100)                        // max tokens ever held
    .refillGreedy(100, Duration.ofHours(1)) // refill 100 tokens over 1 hour
    .build();
```

"Greedy" means the 100 tokens are distributed evenly: roughly 1 token every 36 seconds. A user who makes exactly 1 request every 36 seconds can sustain indefinitely. A user who bursts 100 requests at once must wait an hour for a full refill.

### 4.2 Bucket4j ProxyManager

In a local (non-distributed) setup, `Bucket4j.builder().build()` creates a bucket in the JVM heap. For distributed deployments, `ProxyManager` provides the same API but stores the bucket state in an external store (Redis, Hazelcast, DynamoDB, etc.).

```java
ProxyManager<byte[]> proxyManager = ...;

// Looks up the bucket in Redis by key.
// If it doesn't exist yet, atomically initialises it with the config.
Bucket bucket = proxyManager.builder().build(keyBytes, bucketConfig);

boolean allowed = bucket.tryConsume(1);
```

`LettuceBasedProxyManager` uses **compare-and-swap (CAS)** Lua scripts in Redis:

1. Read the current bucket state.
2. Compute the new state after consuming 1 token.
3. Write the new state only if the current state matches what was read (optimistic locking).
4. If another request raced and changed the state, retry.

This means no Redis WATCH/MULTI/EXEC transactions are needed — the Lua script runs atomically on the Redis server.

### 4.3 ExpirationAfterWriteStrategy

Without an expiration, bucket keys accumulate in Redis forever — one key per user, never cleaned up. `ExpirationAfterWriteStrategy.fixedTimeToLive(Duration.ofHours(2))` instructs Bucket4j to set a Redis TTL of 2 hours on every write to a bucket key. If a user makes no requests for 2 hours, Redis reclaims the memory automatically. The next request initialises a fresh, full bucket.

The TTL is set to 2 hours rather than 1 (the refill window) because a bucket that was partially consumed 59 minutes ago still has valid state — we don't want Redis to evict it right as it would naturally refill.

### 4.4 LettuceConnectionFactory.getNativeClient()

Spring Boot's `spring-boot-starter-data-redis` auto-configures a `LettuceConnectionFactory`. This factory manages the Lettuce `RedisClient` lifecycle (connection pooling, reconnection). `getNativeClient()` exposes the underlying `AbstractRedisClient`. We cast it to `RedisClient` (the concrete non-cluster subclass) and hand it to Bucket4j, which opens its own connection for CAS operations.

The cast is safe for single-node and sentinel Redis deployments. If you later switch to Redis Cluster, `LettuceConnectionFactory` would wrap a `RedisClusterClient` and the cast would fail at startup — a deliberate fast-fail rather than silent misbehaviour. Bucket4j has a `builderFor(RedisClusterClient)` overload for that case.

---

## 5. Files changed

### 5.1 `pom.xml`

Added two Bucket4j dependencies:

```xml
<!-- Bucket4j — token-bucket rate limiting core -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-core</artifactId>
    <version>8.10.1</version>
</dependency>

<!-- Bucket4j Redis backend — LettuceBasedProxyManager for distributed state -->
<dependency>
    <groupId>com.bucket4j</groupId>
    <artifactId>bucket4j-redis</artifactId>
    <version>8.10.1</version>
</dependency>
```

`bucket4j-core` contains the algorithm and in-memory bucket API. `bucket4j-redis` contains the `LettuceBasedProxyManager` and Lua scripts that store bucket state in Redis.

### 5.2 `src/main/java/com/studylink/blogcms/config/RateLimitConfig.java` (new)

Defines three `Bandwidth` constants (one per role) and the `LettuceBasedProxyManager` bean.

Key excerpt — the ProxyManager bean:

```java
@Bean
public LettuceBasedProxyManager<byte[]> rateLimitProxyManager(
        LettuceConnectionFactory lettuceConnectionFactory) {

    RedisClient redisClient = (RedisClient) lettuceConnectionFactory.getNativeClient();

    return LettuceBasedProxyManager.builderFor(redisClient)
            .withExpirationStrategy(
                ExpirationAfterWriteStrategy.fixedTimeToLive(Duration.ofHours(2)))
            .build();
}
```

Key excerpt — the Bandwidth constants:

```java
public static final Bandwidth SUBSCRIBER_BANDWIDTH =
        Bandwidth.builder()
                .capacity(100)
                .refillGreedy(100, Duration.ofHours(1))
                .build();

public static final Bandwidth AUTHOR_BANDWIDTH =
        Bandwidth.builder()
                .capacity(500)
                .refillGreedy(500, Duration.ofHours(1))
                .build();

public static final Bandwidth ADMIN_BANDWIDTH =
        Bandwidth.builder()
                .capacity(Long.MAX_VALUE / 2)
                .refillGreedy(Long.MAX_VALUE / 2, Duration.ofHours(1))
                .build();
```

`Long.MAX_VALUE / 2` is used instead of `Long.MAX_VALUE` to avoid arithmetic overflow inside Bucket4j when it computes refill amounts. At ~4.6 quadrillion requests per hour, no real admin will ever hit this cap.

### 5.3 `src/main/java/com/studylink/blogcms/security/RateLimitFilter.java` (new)

`OncePerRequestFilter` that intercepts every request:

```java
@Override
protected void doFilterInternal(...) throws ServletException, IOException {

    String bucketKeyStr = resolveBucketKey(request);      // e.g. "rate_limit:alice:AUTHOR"
    byte[] bucketKey = bucketKeyStr.getBytes(UTF_8);       // ProxyManager uses byte[] keys

    BucketConfiguration configuration = BucketConfiguration.builder()
            .addLimit(resolveBandwidth(request))
            .build();

    Bucket bucket = rateLimitProxyManager.builder().build(bucketKey, configuration);

    if (bucket.tryConsume(1)) {
        filterChain.doFilter(request, response);           // allowed — continue
    } else {
        sendTooManyRequestsResponse(response, bucketKeyStr); // blocked — HTTP 429
    }
}
```

The `resolveBucketKey` method differentiates authenticated from anonymous callers:

```java
private String resolveBucketKey(HttpServletRequest request) {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (isAuthenticated(auth)) {
        User user = (User) auth.getPrincipal();
        return KEY_PREFIX + user.getUsername() + ":" + user.getRole().name();
    }
    return KEY_PREFIX + "anon:" + resolveClientIp(request);
}
```

`resolveClientIp` checks `X-Forwarded-For` first so that the real client IP is used when the app sits behind an Nginx/ALB reverse proxy.

The 429 response body matches the structure used by `GlobalExceptionHandler`:

```json
{
  "status": 429,
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Try again later."
}
```

`/actuator/**` is excluded via `shouldNotFilter()` so health checks and Prometheus scraping are never throttled.

### 5.4 `src/main/java/com/studylink/blogcms/config/SecurityConfig.java` (modified)

Injected `RateLimitFilter` and registered it after `JwtAuthenticationFilter`:

```java
// Before:
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

// After:
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
```

### 5.5 `src/main/resources/application.properties` (modified)

Added two documentation-only properties that reflect the configured limits. The filter reads the `Bandwidth` constants directly from `RateLimitConfig` rather than from properties, but having them in `application.properties` serves as a single-place reference:

```properties
rate.limit.subscriber-requests-per-hour=100
rate.limit.author-requests-per-hour=500
```

---

## 6. Testing checklist

Requires: app running (`./mvnw spring-boot:run`), Redis running locally on port 6379, and at least one registered user of each role.

| What to test | How to test | Expected result |
|---|---|---|
| Normal authenticated request is allowed | Send any GET `/api/v1/posts` with a valid AUTHOR JWT | HTTP 200, `X-RateLimit` headers not present (we log, not header) |
| SUBSCRIBER limit is enforced | Send 101 GET requests with a SUBSCRIBER JWT in a loop: `for i in $(seq 1 101); do curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $TOKEN" http://localhost:8082/api/v1/posts; done` | First 100 return 200, request 101 returns 429 |
| 429 body is correct JSON | Inspect the 429 response body | `{"status":429,"error":"Too Many Requests","message":"Rate limit exceeded. Try again later."}` |
| AUTHOR gets higher limit | Send 501 requests with an AUTHOR JWT | First 500 succeed, 501st returns 429 |
| ADMIN is never blocked | Send 1000 requests with an ADMIN JWT | All return 200 |
| Anonymous caller gets SUBSCRIBER limit | Send 101 requests without any Authorization header | First 100 return 200/401 (endpoint dependent), 101st returns 429 |
| Actuator is not rate-limited | Send 200 GET requests to `/actuator/health` | All return 200, never 429 |
| Bucket persists across restarts | Consume 50 of 100 SUBSCRIBER tokens, restart the app, send 1 more request | Request succeeds (only ~50 tokens remain in Redis, not a fresh 100) |
| Redis key is visible | After a few requests: `redis-cli keys "rate_limit:*"` | Keys like `rate_limit:alice:SUBSCRIBER` appear |
| Key expires | Wait 2+ hours idle (or manually set TTL to 5s in code for testing) | `redis-cli keys "rate_limit:*"` returns empty after TTL |
