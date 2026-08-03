# Tutorial: Redis Caching in Spring Boot and Spring Expression Language (SpEL)

This tutorial explains everything behind commit 35: what Spring's caching abstraction is, how `@Cacheable` works under the hood, how SpEL expressions power cache keys, and — most importantly — a complete step-by-step flow so you can watch the cache being populated and verified in real time using `redis-cli`. It assumes you have read commits 34 and 35 and have Redis and the app running locally.

---

## Table of Contents

1. [What Problem Does Caching Solve?](#1-what-problem-does-caching-solve)
2. [Spring's Caching Abstraction — The Big Picture](#2-springs-caching-abstraction--the-big-picture)
3. [How `@Cacheable` Intercepts a Method Call](#3-how-cacheable-intercepts-a-method-call)
4. [SpEL — Spring Expression Language from First Principles](#4-spel--spring-expression-language-from-first-principles)
5. [SpEL in Cache Keys — Every Expression This Project Uses](#5-spel-in-cache-keys--every-expression-this-project-uses)
6. [Common SpEL Mistakes and How to Catch Them](#6-common-spel-mistakes-and-how-to-catch-them)
7. [Testing Redis Caching End-to-End — Step-by-Step Flow](#7-testing-redis-caching-end-to-end--step-by-step-flow)
8. [Reading and Inspecting Redis Keys with `redis-cli`](#8-reading-and-inspecting-redis-keys-with-redis-cli)
9. [Proving the Cache Works — Checking SQL Logs](#9-proving-the-cache-works--checking-sql-logs)
10. [TTL — Watching a Cache Entry Expire](#10-ttl--watching-a-cache-entry-expire)
11. [Quick Reference: SpEL Syntax Cheat Sheet](#11-quick-reference-spel-syntax-cheat-sheet)

---

## 1. What Problem Does Caching Solve?

Every call to `getPostById(42)` today does this:

```
HTTP Request
    │
    ▼
PostController.getPostById(42)
    │
    ▼
PostServiceImpl.getPostById(42)
    │
    ▼
PostRepository.findByIdAndIsDeletedFalse(42)   ← SQL round-trip to PostgreSQL
    │
    ▼
SELECT p.*, u.*, c.*, t.*
FROM posts p
LEFT JOIN users u ON ...
LEFT JOIN categories c ON ...
LEFT JOIN post_tags pt ON ...
LEFT JOIN tags t ON ...
WHERE p.id = 42 AND p.is_deleted = false
    │
    ▼
Map entity → PostResponse
    │
    ▼
HTTP Response
```

Every request to the same post triggers the same SQL. The post hasn't changed, but the database still:

- Parses the SQL
- Checks the query planner cache
- Traverses the B-tree index on `posts.id`
- Fetches the heap pages for the post, user, category, and tags
- Deserializes the rows into Java objects
- Maps them to a DTO

For a blog where the top 10 posts receive 90% of traffic, this is waste. The data for post 42 has not changed since the last request 50 milliseconds ago.

Caching solves this by storing the already-computed `PostResponse` in Redis after the first call. Every subsequent call for the same post ID skips the database entirely.

---

## 2. Spring's Caching Abstraction — The Big Picture

Spring's caching layer is a **provider-agnostic abstraction**. Your service code annotates methods with `@Cacheable`. Spring's infrastructure decides where to store the cached value — in memory (Caffeine), in a distributed store (Redis), or anywhere else.

```
Your Code        Spring Abstraction         Storage Backend
─────────────    ──────────────────────     ───────────────
@Cacheable   →   CacheManager           →   RedisTemplate (commit 34)
@CacheEvict  →   Cache                  →   Redis keyspace
@CachePut    →   CacheInterceptor
```

The key components wired up in commit 34:

| Component | Class | Role |
|---|---|---|
| `@EnableCaching` | `RedisCacheConfig` | Activates the proxy that intercepts `@Cacheable` calls |
| `RedisCacheManager` | `RedisCacheConfig` | Knows about named caches (`posts`) and their TTLs |
| `RedisTemplate` | Auto-configured by Spring Boot | The actual Redis client that serializes/deserializes |
| `GenericJackson2JsonRedisSerializer` | `RedisCacheConfig` | Converts `PostResponse` ↔ JSON bytes in Redis |

Commit 35 adds only the annotations. The infrastructure was already in place.

---

## 3. How `@Cacheable` Intercepts a Method Call

`@Cacheable` works through **Spring AOP** — the same proxy mechanism used by `@Transactional`. When Spring builds the `PostServiceImpl` bean, it wraps it in a generated proxy class.

```
What you write in code:          What Spring actually creates at runtime:
─────────────────────────        ──────────────────────────────────────────
@Service                         $PostServiceImpl$$SpringCGLIB$$0  ← proxy
class PostServiceImpl {              (wraps real PostServiceImpl)
    @Cacheable(...)
    public PostResponse              intercepts every @Cacheable call
        getPostById(...) { ... }
}
```

When `PostController` calls `postService.getPostById(42)`, it is actually calling the **proxy's** method. The proxy runs this logic before delegating to your real method:

```
proxy.getPostById(42)
    │
    ├── Step 1: Evaluate SpEL key expression
    │       key = "#postId"  →  "42"
    │
    ├── Step 2: Look up the "posts" cache in RedisCacheManager
    │
    ├── Step 3: Ask Redis: GET "posts::42"
    │       │
    │       ├── CACHE HIT
    │       │       Deserialize JSON bytes → PostResponse
    │       │       Return immediately (real method never called)
    │       │
    │       └── CACHE MISS
    │               Call real PostServiceImpl.getPostById(42)
    │               SQL executes, entity fetched, mapped to PostResponse
    │               Redis SET "posts::42" <json> EX 300
    │               Return PostResponse
    │
    └── Return result to PostController
```

**Critical rule:** The proxy only intercepts calls that come **from outside the bean**. If `PostServiceImpl` calls its own `getPostById` internally (a self-call), the proxy is bypassed entirely and caching does not work. This is a very common `@Cacheable` gotcha.

---

## 4. SpEL — Spring Expression Language from First Principles

SpEL is a runtime expression language built into Spring. It lets you write small expressions inside annotation attributes that Spring evaluates dynamically at runtime — similar to how Thymeleaf uses `${}` in templates.

### Where SpEL appears in this project

```java
@Cacheable(value = "posts", key = "#postId")
//                           ^^^^^^^^^^^^^ this is a SpEL expression
```

The string `"#postId"` is not a plain Java string. Spring passes it to the SpEL parser, which evaluates it against a special **evaluation context** that Spring populates before every method call.

### The SpEL evaluation context for `@Cacheable`

Spring populates the SpEL context with these variables automatically when evaluating a cache key:

| SpEL variable | What it refers to |
|---|---|
| `#paramName` | The value of the method parameter named `paramName` |
| `#root.method` | The `java.lang.reflect.Method` object being called |
| `#root.target` | The target object (i.e., `PostServiceImpl` instance) |
| `#root.caches` | The list of caches the annotation targets |
| `#root.args[n]` | The nth argument by position (0-indexed) |
| `#result` | The return value — only available in `@CachePut` and `@CacheEvict`'s `condition` |

For `@Cacheable`, the most important are `#paramName` variables, because that is how you build a key from the method's inputs.

### SpEL types and operators

SpEL supports:

```
Literals
    'hello'        ← string (single quotes)
    42             ← integer
    true / false   ← boolean

Variable access
    #paramName     ← method parameter by name

String concatenation
    'prefix_' + #paramName + '_suffix'

Arithmetic
    #page + 1
    #size * 2

Comparison (used in condition/unless)
    #page == 0
    #result != null
    #id > 0

Method calls on variables
    #slug.toLowerCase()
    #title.length()

Ternary / Elvis
    #name != null ? #name : 'default'
    #name ?: 'default'   ← Elvis operator (short form)

Safe navigation (avoids NullPointerException)
    #user?.name          ← returns null if user is null
```

---

## 5. SpEL in Cache Keys — Every Expression This Project Uses

### `getPostById` — simple parameter reference

```java
@Cacheable(value = "posts", key = "#postId")
public PostResponse getPostById(Long postId)
```

| Aspect | Detail |
|---|---|
| SpEL expression | `#postId` |
| Type | Parameter reference |
| Runtime value (e.g. postId=42) | `42` (the Long, coerced to String) |
| Redis key | `posts::42` |

**Why `#postId` and not `#id`?**

SpEL resolves `#varName` by looking up the method parameter **by its Java source name**. The parameter is declared as `Long postId`. If you wrote `#id`, SpEL cannot find a parameter named `id`, evaluates to `null`, and the Redis key becomes `posts::null`. Every post would overwrite the same cache entry. Always match the SpEL variable to the exact parameter name.

---

### `getPostBySlug` — string parameter reference

```java
@Cacheable(value = "posts", key = "#slug")
public PostResponse getPostBySlug(String slug)
```

| Aspect | Detail |
|---|---|
| SpEL expression | `#slug` |
| Runtime value (e.g. slug="my-first-post") | `"my-first-post"` |
| Redis key | `posts::my-first-post` |

A String parameter works exactly like a Long — SpEL uses its `.toString()` value in the key.

---

### `getAllPosts` — composite key with a condition

```java
@Cacheable(
    value = "posts",
    key = "'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir",
    condition = "#page == 0"
)
public Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir)
```

| Aspect | Detail |
|---|---|
| SpEL key expression | `'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir` |
| SpEL condition | `#page == 0` |
| Runtime value (page=0, size=10, sortBy="createdAt", sortDir="desc") | `"all_0_10_createdAt_desc"` |
| Redis key | `posts::all_0_10_createdAt_desc` |

**The string literal `'all_'`**: In SpEL, string literals use **single quotes**. This is different from Java where you use double quotes. The double quotes are the Java string that wraps the whole SpEL expression; inside SpEL, you need single quotes for literal strings.

```
Java string:   "  'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir  "
                   ^^^^^^                ^^^                     ^^^
                   SpEL string literals — single quotes inside the Java double-quoted string
```

**The `condition` attribute**: Evaluated before the method runs. If `page != 0`, the caching proxy completely ignores this call — no Redis read, no Redis write. The method executes normally and returns its result without any caching.

---

### `getPostsByCategory` — composite key without condition

```java
@Cacheable(
    value = "posts",
    key = "'category_' + #categoryId + '_' + #page + '_' + #size"
)
public Page<PostResponse> getPostsByCategory(Long categoryId, int page, int size)
```

| Aspect | Detail |
|---|---|
| SpEL expression | `'category_' + #categoryId + '_' + #page + '_' + #size` |
| Runtime value (categoryId=3, page=0, size=10) | `"category_3_0_10"` |
| Redis key | `posts::category_3_0_10` |

All pages are cached here (no `condition`). The prefix `'category_'` prevents collisions with the `'all_'` keys and with plain numeric ID keys.

---

## 6. Common SpEL Mistakes and How to Catch Them

### Mistake 1: Wrong parameter name → `posts::null`

```java
// Method signature:
public PostResponse getPostById(Long postId)

// WRONG — #id does not match parameter name "postId"
@Cacheable(value = "posts", key = "#id")

// CORRECT
@Cacheable(value = "posts", key = "#postId")
```

**How to detect it:** `redis-cli KEYS "posts::null"` will return a result. All post ID lookups are colliding.

---

### Mistake 2: Java double quotes inside SpEL string literal

```java
// WRONG — double quotes are not SpEL string literals
key = "'all_' + #page"       // correct
key = "\"all_\" + #page"     // this is not SpEL syntax, will throw EvaluationException
```

Always use single quotes for literal strings inside SpEL expressions.

---

### Mistake 3: Caching a method that calls `this.*` internally

```java
// Inside PostServiceImpl — self-call, proxy is bypassed
public PostResponse someOtherMethod(Long postId) {
    return this.getPostById(postId);   // @Cacheable on getPostById is IGNORED here
}
```

The `@Cacheable` annotation is only active when the call comes through the Spring proxy (i.e., from another bean). Self-calls bypass the proxy.

---

### Mistake 4: Placing `@Cacheable` on a `private` method

```java
// WRONG — Spring's CGLIB proxy cannot intercept private methods
@Cacheable(value = "posts", key = "#id")
private PostResponse getPostById(Long id) { ... }
```

Spring will silently ignore the annotation — no exception is thrown. Always put `@Cacheable` on `public` methods.

---

### Mistake 5: Forgetting `@EnableCaching`

Without `@EnableCaching` on a `@Configuration` class, `@Cacheable` annotations are present in bytecode but the caching proxy is never created. The annotations do nothing. This project enables caching in `RedisCacheConfig`.

---

## 7. Testing Redis Caching End-to-End — Step-by-Step Flow

This section walks you through a complete test run. You will see the cache being populated, verify keys in Redis, confirm cache hits eliminate SQL queries, and watch a key expire.

### Prerequisites

Before starting, make sure:

1. **Redis is running locally on port 6379**

```bash
redis-cli ping
# Expected: PONG
```

2. **The application is running with SQL logging enabled.** Open `application.properties` (or `application.yml`) and confirm or temporarily add:

```properties
spring.jpa.show-sql=true
logging.level.org.hibernate.SQL=DEBUG
```

Restart the app after changing properties.

3. **You have a post seeded in the database.** The dev seed profile creates posts automatically. Start the app with:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

4. **Get an auth token** (some endpoints require JWT). Register or log in:

```bash
curl -s -X POST http://localhost:8082/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"password123"}' \
  | jq '.token'
```

Save the token:

```bash
TOKEN="<paste token here>"
```

---

### Flow 1 — `getPostById`: watch a cache entry appear

**Step 1.1 — Flush Redis so we start clean**

```bash
redis-cli FLUSHDB
```

Expected:

```
OK
```

**Step 1.2 — Confirm Redis is empty**

```bash
redis-cli KEYS "*"
```

Expected: `(empty array)`

**Step 1.3 — Hit the endpoint for the first time (CACHE MISS)**

```bash
curl -s http://localhost:8082/api/v1/posts/1 | jq '.id, .title'
```

Watch the application log. You should see Hibernate SQL:

```
Hibernate: select p1_0.id, p1_0.author_id, p1_0.category_id, ...
           from posts p1_0
           left join users u1_0 on u1_0.id=p1_0.author_id
           ...
           where p1_0.id=? and p1_0.is_deleted=false
```

This confirms the request hit the database.

**Step 1.4 — Verify the Redis key was created**

```bash
redis-cli KEYS "posts::*"
```

Expected:

```
1) "posts::1"
```

**Step 1.5 — Hit the endpoint a second time (CACHE HIT)**

```bash
curl -s http://localhost:8082/api/v1/posts/1 | jq '.id, .title'
```

Watch the application log. You should see **no Hibernate SQL** this time. The response came entirely from Redis without touching PostgreSQL.

**Step 1.6 — Inspect the raw value stored in Redis**

```bash
redis-cli GET "posts::1"
```

You will see JSON starting with something like:

```json
{"@class":"com.studylink.blogcms.dto.response.PostResponse","id":1,"title":"Getting Started...","slug":"getting-started-...","content":"..."}
```

The `@class` field is written by `GenericJackson2JsonRedisSerializer` so Spring knows which Java class to deserialize back to when reading from Redis.

---

### Flow 2 — `getPostBySlug`: string-keyed cache entry

**Step 2.1 — Find the slug of post 1**

```bash
curl -s http://localhost:8082/api/v1/posts/1 | jq '.slug'
```

Note the slug, e.g. `"getting-started-with-spring-boot"`.

**Step 2.2 — Hit the slug endpoint (first call = CACHE MISS)**

```bash
SLUG="getting-started-with-spring-boot"
curl -s http://localhost:8082/api/v1/posts/slug/$SLUG | jq '.id, .slug'
```

You should see SQL in the logs.

**Step 2.3 — Verify the slug key exists in Redis**

```bash
redis-cli KEYS "posts::*"
```

Expected (both keys may exist now):

```
1) "posts::1"
2) "posts::getting-started-with-spring-boot"
```

**Step 2.4 — Second call via slug (CACHE HIT)**

```bash
curl -s http://localhost:8082/api/v1/posts/slug/$SLUG | jq '.id'
```

No SQL in logs. The result came from `posts::getting-started-with-spring-boot` in Redis.

---

### Flow 3 — `getAllPosts`: composite key, page 0 cached, page 1 not cached

**Step 3.1 — Flush Redis**

```bash
redis-cli FLUSHDB
```

**Step 3.2 — Request page 0 (CACHE MISS → stores in Redis)**

```bash
curl -s "http://localhost:8082/api/v1/posts?page=0&size=10&sortBy=createdAt&sortDir=desc" \
  | jq '.totalElements, .number'
```

SQL appears in logs.

**Step 3.3 — Verify the composite key in Redis**

```bash
redis-cli KEYS "posts::all*"
```

Expected:

```
1) "posts::all_0_10_createdAt_desc"
```

**Step 3.4 — Request page 0 again (CACHE HIT)**

```bash
curl -s "http://localhost:8082/api/v1/posts?page=0&size=10&sortBy=createdAt&sortDir=desc" \
  | jq '.totalElements, .number'
```

No SQL in logs.

**Step 3.5 — Request page 1 (condition `#page == 0` is false → NOT cached)**

```bash
curl -s "http://localhost:8082/api/v1/posts?page=1&size=10&sortBy=createdAt&sortDir=desc" \
  | jq '.number'
```

SQL appears in logs (cache miss path). Call it again:

```bash
curl -s "http://localhost:8082/api/v1/posts?page=1&size=10&sortBy=createdAt&sortDir=desc" \
  | jq '.number'
```

SQL appears again. Page 1 is **never** stored in Redis because `condition = "#page == 0"` blocked it.

**Step 3.6 — Confirm no page-1 key in Redis**

```bash
redis-cli KEYS "posts::all*"
```

Only `posts::all_0_10_createdAt_desc` should appear — no key for page 1.

---

### Flow 4 — `getPostsByCategory`: per-category cache

**Step 4.1 — Find a category ID**

```bash
curl -s http://localhost:8082/api/v1/categories | jq '.[0].id'
```

Note the category ID, e.g. `3`.

**Step 4.2 — Request posts by category (CACHE MISS)**

```bash
curl -s "http://localhost:8082/api/v1/posts/category/3?page=0&size=10" \
  | jq '.totalElements'
```

SQL in logs.

**Step 4.3 — Verify the key**

```bash
redis-cli KEYS "posts::category*"
```

Expected:

```
1) "posts::category_3_0_10"
```

**Step 4.4 — Second call (CACHE HIT)**

```bash
curl -s "http://localhost:8082/api/v1/posts/category/3?page=0&size=10" \
  | jq '.totalElements'
```

No SQL.

---

## 8. Reading and Inspecting Redis Keys with `redis-cli`

### List all keys in the `posts` cache

```bash
redis-cli KEYS "posts::*"
```

### Get the serialized value of a key

```bash
redis-cli GET "posts::1"
```

The value is stored as a JSON string. It includes the `@class` field added by `GenericJackson2JsonRedisSerializer`.

### Check how many seconds are left before a key expires

```bash
redis-cli TTL "posts::1"
```

- Returns the number of seconds remaining (e.g. `287`)
- Returns `-1` if the key has no expiry (it shouldn't — all keys should have a TTL)
- Returns `-2` if the key does not exist

The TTL for the `posts` cache is **300 seconds** (5 minutes), configured in `RedisCacheConfig` in commit 34.

### Check the exact expiry time as a Unix timestamp

```bash
redis-cli EXPIRETIME "posts::1"
```

### Count all cached post entries

```bash
redis-cli KEYS "posts::*" | wc -l
```

### Inspect all keys in the entire Redis database

```bash
redis-cli KEYS "*"
```

### Delete a specific key manually (simulate cache eviction)

```bash
redis-cli DEL "posts::1"
```

After this, the next request to `GET /api/v1/posts/1` will trigger a cache miss and SQL will appear in the logs again.

### Flush the entire cache (useful between test runs)

```bash
redis-cli FLUSHDB
```

---

## 9. Proving the Cache Works — Checking SQL Logs

The clearest evidence that caching is working is the **absence of SQL** in the application logs on the second request. Here is what to look for.

### Turn on SQL logging

In `src/main/resources/application.properties`:

```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.orm.jdbc.bind=TRACE
```

`format_sql=true` pretty-prints the SQL so it is easier to read. `jdbc.bind=TRACE` shows the parameter values (e.g. `binding parameter (1:BIGINT) <- [42]`).

### What a cache miss looks like in logs

```
DEBUG org.hibernate.SQL - 
    select
        p1_0.id,
        p1_0.author_id,
        ...
    from
        posts p1_0
    left join
        users u1_0 on u1_0.id=p1_0.author_id
    ...
    where
        p1_0.id=?
        and p1_0.is_deleted=false
TRACE org.hibernate.orm.jdbc.bind - binding parameter (1:BIGINT) <- [1]
```

### What a cache hit looks like in logs

Nothing. Silence in the Hibernate log output. The result came from Redis before Hibernate was involved.

This is the clearest way to prove caching is working — not by looking at what the log says, but by looking at what it **does not say**.

---

## 10. TTL — Watching a Cache Entry Expire

The TTL (time-to-live) for the `posts` cache is 300 seconds. You can watch a key expire in real time.

**Step 1 — Create a cache entry**

```bash
curl -s http://localhost:8082/api/v1/posts/1 > /dev/null
```

**Step 2 — Check the TTL**

```bash
redis-cli TTL "posts::1"
# e.g. 298
```

**Step 3 — Wait a minute and check again**

```bash
sleep 60 && redis-cli TTL "posts::1"
# e.g. 238
```

**Step 4 — After 300 seconds, the key is gone**

```bash
redis-cli TTL "posts::1"
# -2  ← key does not exist
```

```bash
redis-cli KEYS "posts::1"
# (empty array)
```

**Step 5 — Next request triggers a cache miss again**

```bash
curl -s http://localhost:8082/api/v1/posts/1 | jq '.id'
```

SQL reappears in the logs. The cache repopulates.

This is the TTL mechanism at work — Redis automatically evicts the key after 300 seconds. The next request pays the database cost once, then the cache is warm again.

---

## 11. Quick Reference: SpEL Syntax Cheat Sheet

| Use case | SpEL syntax | Example result |
|---|---|---|
| Method parameter by name | `#paramName` | `42`, `"my-slug"` |
| String literal prefix | `'prefix_'` | `"prefix_"` |
| Concatenate strings | `'prefix_' + #param` | `"prefix_42"` |
| Concatenate multiple params | `#a + '_' + #b + '_' + #c` | `"0_10_desc"` |
| Condition: only page 0 | `condition = "#page == 0"` | Caches page 0 only |
| Skip null results | `unless = "#result == null"` | Not stored if null |
| Access nested property | `#user.id` | User's ID field |
| Safe navigation (null-safe) | `#user?.id` | null if user is null |
| Use positional arg | `#root.args[0]` | First method argument |
| Elvis operator | `#name ?: 'unknown'` | `"unknown"` if name is null |

### Redis key anatomy

```
posts        ::      all_0_10_createdAt_desc
│                    │
│                    └── SpEL key expression result
│
└── cache name (value = "posts" in @Cacheable)
```

Spring always formats the final Redis key as `<cacheName>::<spelResult>`.
