# Commit 34 — Redis Cache Configuration

## Overview

This commit adds Redis as a caching layer to the Blog CMS. It introduces the `spring-boot-starter-data-redis` Maven dependency, adds Redis connection and TTL properties to `application.properties`, and creates a dedicated `RedisCacheConfig` configuration class in the `config/` package. The config class enables Spring's caching abstraction with `@EnableCaching`, builds a `RedisCacheManager` bean with per-cache TTLs for the `posts`, `categories`, and `tags` caches, uses Jackson JSON serialization for cache values instead of JDK binary serialization, and falls back to a configurable default TTL for any other cache name.

No service methods are annotated with `@Cacheable` or `@CacheEvict` yet — this commit only sets up the infrastructure. Annotating individual service methods is intentional follow-up work, keeping the infrastructure concern and the business concern in separate commits.

---

## Why This Was Needed

The blog CMS serves public read-heavy endpoints — `GET /api/v1/posts`, `GET /api/v1/categories`, `GET /api/v1/tags` — from a PostgreSQL database. Every request hits the database even when the underlying data has not changed. For a teaching project that already demonstrates full-text search (GIN index, commit 33), N+1 prevention (`@EntityGraph`, commit 25), and connection pooling, caching is the next logical performance topic to introduce. Redis is the industry-standard choice for distributed caching behind Spring Boot applications.

---

## Decisions Made

### 1. Lettuce vs Jedis as the Redis client

**Option A — Jedis**
Jedis is a synchronous, connection-pool-based Redis client. It requires adding `jedis` explicitly and excluding Lettuce from the starter.

*Rejected because:* It requires extra exclusion/addition in `pom.xml` for no benefit in this project. The default is Lettuce, which is already managed by the Spring Boot BOM.

**Option B — Lettuce (chosen)**
Lettuce is the default client bundled by `spring-boot-starter-data-redis`. It is non-blocking (Netty-based), thread-safe (a single connection can be shared), and fully managed by the Spring Boot auto-configuration.

*Chosen because:* Zero extra configuration. Spring Boot 4 ships with Lettuce support out of the box. Adding the starter is the only Maven change needed.

---

### 2. Where to place `@EnableCaching`

**Option A — On `BlogCmsApplication`**
Spring Boot docs mention that `@EnableCaching` can go on the main class alongside `@SpringBootApplication`.

*Rejected because:* The project convention is to isolate infrastructure concerns in dedicated config classes (`JpaConfig` for JPA auditing, `SecurityConfig` for Spring Security). Putting `@EnableCaching` on `BlogCmsApplication` would break that pattern and mix responsibilities.

**Option B — On a dedicated config class (chosen)**
Create `RedisCacheConfig.java` in `config/`, annotate it with both `@Configuration` and `@EnableCaching`, and define the `RedisCacheManager` bean in the same file.

*Chosen because:* Co-locating `@EnableCaching` with the `RedisCacheManager` bean definition makes the relationship obvious: the annotation says "enable caching"; the bean says "use this specific manager". Everything about Redis caching is in one file. Consistent with how `SecurityConfig` owns `@EnableWebSecurity` + `@EnableMethodSecurity`.

---

### 3. JDK serialization vs Jackson JSON for cache values

**Option A — JDK serialization (Spring Boot default)**
Spring Data Redis uses `JdkSerializationRedisSerializer` by default if no value serializer is configured. Every cached object is written as an opaque binary blob.

*Rejected because:*
- Cached objects must implement `java.io.Serializable`. Response DTOs in this project don't implement it and shouldn't need to.
- Serialized bytes are unreadable with `redis-cli`, making debugging painful.
- Binary format is brittle — adding or renaming a field in a DTO can cause deserialization errors on existing cache entries.
- If the cache is ever shared between two JVM versions or inspected externally, the format is meaningless.

**Option B — Jackson JSON (chosen)**
Use `GenericJacksonJsonRedisSerializer` which writes human-readable JSON. The fully-qualified class name is embedded in the document (default typing), allowing deserialization without needing to know the target type at compile time.

*Chosen because:*
- No `Serializable` requirement on any DTO.
- Cache entries are inspectable with `redis-cli GET <key>`.
- Adding a new field to a DTO does not break existing cache entries (unknown fields are ignored).
- Consistent with how this project serializes HTTP responses — JSON everywhere.

---

### 4. Jackson 2 vs Jackson 3 serializer class

This decision arose during implementation and deserves its own section because it is specific to Spring Boot 4.

Spring Boot 4.0.1 uses **Jackson 3** (`tools.jackson.*` namespace), not Jackson 2 (`com.fasterxml.jackson.*`). The Jackson 3 group ID is `tools.jackson.core` and the package root changed from `com.fasterxml.jackson` to `tools.jackson`. The two versions coexist in the dependency tree (Jackson 2 is pulled in transitively by `jjwt-jackson`) but Spring Boot 4's auto-configuration is built on Jackson 3.

Spring Data Redis 4.0.1 ships two serializer classes reflecting this transition:

| Class | Jackson version | Package |
|---|---|---|
| `GenericJackson2JsonRedisSerializer` | Jackson 2 | `com.fasterxml.jackson` |
| `GenericJacksonJsonRedisSerializer` | Jackson 3 | `tools.jackson` |

The first attempt used `GenericJackson2JsonRedisSerializer` (Jackson 2) and also imported `JavaTimeModule` from `com.fasterxml.jackson.datatype.jsr310`. This caused a compilation failure because `jackson-datatype-jsr310` is not on the classpath — Spring Boot 4 does not include it (Java time support is built into Jackson 3's `jackson-databind`).

**Chosen approach:** Use `GenericJacksonJsonRedisSerializer` (Jackson 3). Its builder API provides `enableUnsafeDefaultTyping()` which is the Jackson 3 equivalent of Jackson 2's `activateDefaultTyping(LaissezFaireSubTypeValidator, NON_FINAL, AS.PROPERTY)`. Java 8 date/time support is included automatically in Jackson 3's core `jackson-databind`.

---

### 5. Per-cache TTL design: builder vs `RedisCacheManagerBuilderCustomizer`

**Option A — `RedisCacheManagerBuilderCustomizer` bean**
Spring Boot provides `RedisCacheManagerBuilderCustomizer` as a hook to modify the auto-configured `RedisCacheManager`. You register a `@Bean` of this type and receive the builder.

*Rejected because:* This relies on Spring Boot's Redis cache auto-configuration being active. If we are registering our own `RedisCacheManager` bean (which overrides auto-configuration anyway), the customizer pattern adds indirection without benefit. Also harder to read — the TTL configuration is separated from the manager definition.

**Option B — Manual `RedisCacheManager.builder(...)` (chosen)**
Build the manager explicitly: define a `defaultCacheConfiguration()` helper, create a `Map<String, RedisCacheConfiguration>` for named caches, and call `RedisCacheManager.builder(connectionFactory).cacheDefaults(...).withInitialCacheConfigurations(...).build()`.

*Chosen because:* All TTL configuration is visible in one method. The relationship between a cache name and its TTL is explicit and readable. This is consistent with how `SecurityConfig` builds its filter chain — explicit, no hidden defaults.

---

## New Concepts Introduced

### Spring Caching Abstraction (`@EnableCaching`, `@Cacheable`, `@CacheEvict`)

Spring's caching abstraction is a proxy-based mechanism that intercepts method calls and either returns a cached result or allows the method to execute and stores its return value. It is technology-agnostic: the same `@Cacheable` annotation works with Redis, Caffeine, Ehcache, or a simple `ConcurrentHashMap`.

`@EnableCaching` activates the mechanism at application startup. Without it, `@Cacheable` annotations are silently ignored. It works by registering a `CacheInterceptor` and a `CacheAspectSupport` around any bean method annotated with `@Cacheable`.

```
@Cacheable("posts")
public Page<PostResponse> getAllPosts(...) { ... }
```

On first call: method executes, result stored in Redis under key `posts::<args>`.
On subsequent calls with same args: method is skipped, cached JSON is deserialized and returned.

`@CacheEvict` removes entries when data changes:

```
@CacheEvict(value = "posts", allEntries = true)
public PostResponse createPost(...) { ... }
```

This commit sets up the infrastructure. Annotating service methods is the next step.

---

### `RedisCacheManager` and `RedisCacheConfiguration`

`RedisCacheManager` is the Spring Data Redis implementation of Spring's `CacheManager` interface. When Spring's caching interceptor needs to read or write a cache entry, it calls `CacheManager.getCache(name)`, which returns a `RedisCache` configured with the TTL, key serializer, and value serializer defined in the corresponding `RedisCacheConfiguration`.

`RedisCacheConfiguration` is immutable — every method that modifies it returns a new instance (builder pattern). This is why `defaultCacheConfiguration().entryTtl(...)` is safe to call multiple times: each call returns a fresh configuration object without mutating the shared default.

```
defaultCacheConfiguration()                     → TTL=120s, Jackson serializer
  .entryTtl(Duration.ofSeconds(postsTtlSeconds)) → TTL=300s, Jackson serializer (new object)
```

---

### Default typing in Jackson (why it is needed for caching)

When Spring deserializes a cached value, it reads raw bytes from Redis and must reconstruct a Java object. If the cache stores a `Page<PostResponse>`, Jackson needs to know it is dealing with `PostResponse` objects inside a `Page` — it cannot infer this from the bytes alone.

Default typing solves this by embedding the Java class name in the JSON:

```json
["com.studylink.blogcms.dto.response.PostResponse", {"id": 1, "title": "Hello"}]
```

When deserializing, Jackson reads the class name from the JSON, instantiates the right class, and populates its fields. Without this, deserialization fails with an `InvalidDefinitionException` for polymorphic or generic types.

`enableUnsafeDefaultTyping()` in the builder enables this behavior. It is labeled "unsafe" because a malicious actor who can write arbitrary JSON to Redis could embed a harmful class name. Since only our application writes to this Redis instance, this risk does not apply here.

---

### `@Value` for configuration injection

`@Value("${cache.ttl.posts:300}")` tells Spring to inject the value of the `cache.ttl.posts` property into the field. The `:300` is the default if the property is absent. This is the same mechanism used for `jwt.secret=${JWT_SECRET:your-secret-key-...}` in `application.properties` — both the property file value and the default are part of the same Spring Environment resolution chain.

For Redis connection settings (`spring.data.redis.*`), `@Value` is not needed in the config class because Spring Boot's `RedisAutoConfiguration` reads those properties and builds the `RedisConnectionFactory` bean automatically. The config class simply declares `RedisConnectionFactory connectionFactory` as a `@Bean` parameter and Spring injects the auto-configured instance.

---

### Lettuce connection pool (`lettuce.pool.*`)

Lettuce by default uses a single shared connection (non-blocking, multiplexed). The pool properties (`max-active`, `max-idle`, `min-idle`, `max-wait`) become active only when `spring-boot-starter-data-redis` detects the `commons-pool2` artifact on the classpath. Without it, Lettuce operates in single-connection mode (fine for most applications). The pool properties are configured in `application.properties` now so they are ready to activate if `commons-pool2` is added later.

---

## Files Changed

### `pom.xml`

Added the Spring Data Redis starter. Lettuce is included transitively — no extra client dependency needed.

```xml
<!-- Redis — Spring Data Redis with Lettuce client (default, non-blocking) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

No version tag — the Spring Boot parent BOM manages the version.

---

### `src/main/resources/application.properties`

Added two blocks of new properties:

```properties
# Redis Configuration
# Override REDIS_PASSWORD env var in production (leave blank for local dev with no auth)
spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}
spring.data.redis.password=${REDIS_PASSWORD:}
spring.data.redis.timeout=2000ms
spring.data.redis.lettuce.pool.max-active=8
spring.data.redis.lettuce.pool.max-idle=8
spring.data.redis.lettuce.pool.min-idle=2
spring.data.redis.lettuce.pool.max-wait=-1ms

# Cache TTL (in seconds) — referenced by RedisCacheConfig
cache.ttl.posts=300
cache.ttl.categories=600
cache.ttl.tags=600
cache.ttl.default=120
```

Key points:
- `REDIS_PASSWORD:` (empty default) — local Redis typically runs without auth. In production, set the env var.
- `REDIS_HOST` and `REDIS_PORT` support env override so the same application.properties works in Docker Compose (`REDIS_HOST=redis-service`).
- TTL values are in the `cache.ttl.*` namespace, separate from `spring.*`, because they are application-specific tuning parameters, not Spring framework settings.
- `timeout=2000ms` — if Redis is unreachable, the connection attempt fails after 2 seconds rather than blocking indefinitely.

---

### `src/main/java/com/studylink/blogcms/config/RedisCacheConfig.java` (new file)

```java
@Slf4j
@Configuration
@EnableCaching
public class RedisCacheConfig {

    @Value("${cache.ttl.posts:300}")
    private long postsTtlSeconds;
    // ... (categoriesTtlSeconds, tagsTtlSeconds, defaultTtlSeconds same pattern)

    private GenericJacksonJsonRedisSerializer jacksonSerializer() {
        return GenericJacksonJsonRedisSerializer.builder()
                .enableUnsafeDefaultTyping()
                .build();
    }

    private RedisCacheConfiguration defaultCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofSeconds(defaultTtlSeconds))
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(new StringRedisSerializer())
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair
                                .fromSerializer(jacksonSerializer())
                );
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        log.info("Initialising RedisCacheManager — posts={}s, categories={}s, tags={}s, default={}s",
                postsTtlSeconds, categoriesTtlSeconds, tagsTtlSeconds, defaultTtlSeconds);

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();
        cacheConfigurations.put("posts",
                defaultCacheConfiguration().entryTtl(Duration.ofSeconds(postsTtlSeconds)));
        cacheConfigurations.put("categories",
                defaultCacheConfiguration().entryTtl(Duration.ofSeconds(categoriesTtlSeconds)));
        cacheConfigurations.put("tags",
                defaultCacheConfiguration().entryTtl(Duration.ofSeconds(tagsTtlSeconds)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultCacheConfiguration())
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }
}
```

Structural notes:
- `@Slf4j` — consistent with project logging rule.
- `@Configuration @EnableCaching` — both required; `@Configuration` makes the class a Spring config class; `@EnableCaching` turns on the proxy infrastructure.
- `jacksonSerializer()` and `defaultCacheConfiguration()` are private helper methods, not beans. They are called multiple times to produce independent instances (because `RedisCacheConfiguration` is immutable and we mutate TTL per cache).
- `cacheManager` is the only `@Bean`. Spring Boot auto-configuration detects a user-defined `CacheManager` bean and backs off its own.
- `disableCachingNullValues()` — prevents `@Cacheable` from caching a null return value, which would permanently hide a "not found" result until the TTL expires.

---

## Architecture diagram

```
HTTP Request
    │
    ▼
Controller
    │
    ▼
Service method (future: @Cacheable("posts"))
    │
    ├─ Cache HIT  →  Redis returns JSON  →  Jackson deserializes  →  return cached DTO
    │
    └─ Cache MISS →  repository query  →  DB returns entity
                         │
                         ▼
                      Mapper (entity → DTO)
                         │
                         ▼
                   Jackson serializes DTO → Redis stores JSON with TTL
                         │
                         ▼
                   return DTO to controller
```

```
RedisCacheManager
  ├── "posts"       → TTL 300s,  Jackson JSON serializer
  ├── "categories"  → TTL 600s,  Jackson JSON serializer
  ├── "tags"        → TTL 600s,  Jackson JSON serializer
  └── (any other)   → TTL 120s,  Jackson JSON serializer  (default)
```

---

## Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Application starts with Redis running | `./mvnw spring-boot:run` with Redis on `localhost:6379` | Application starts, log line `Initialising RedisCacheManager — posts=300s, categories=600s, tags=600s, default=120s` visible |
| Application starts without Redis | Stop Redis, start the app | App starts (cache is not yet used by any service method); if Redis is unreachable the connection error only surfaces on first cache use |
| Compilation is clean | `./mvnw compile -q` | Exit 0, no errors |
| Redis properties respected | Set `REDIS_HOST=myhost REDIS_PORT=6380` and check startup logs | Lettuce connects to `myhost:6380` |
| Password env override | Set `REDIS_PASSWORD=secret` and verify Redis AUTH | Connection authenticated; no password visible in logs |
| Cache manager bean type | Add a test that injects `CacheManager` and asserts `instanceof RedisCacheManager` | Passes; confirms Spring Boot auto-config backed off |
| TTL override | Change `cache.ttl.posts=10` in properties, restart, add `@Cacheable("posts")` temporarily, set a value, wait 11 seconds, `redis-cli TTL <key>` | Key expired after ~10 seconds |
| JSON serialization | After adding `@Cacheable`, call an endpoint, then `redis-cli GET posts::<key>` | Human-readable JSON, not binary; class name embedded in value |
| Null values not cached | If a method returns `null`, it must not be stored | `redis-cli EXISTS <key>` returns 0 after a null-returning call |
