# Commit 35 — @Cacheable Annotations on PostServiceImpl Read Methods

## Overview

This commit activates the Redis caching layer that was wired up in commit 34 by annotating four read methods in `PostServiceImpl` with `@Cacheable`. Before this change, every call to `getPostById`, `getPostBySlug`, `getAllPosts`, and `getPostsByCategory` hit the PostgreSQL database, even when the underlying data had not changed. After this change, the first call to any of these methods executes the database query and stores the result as JSON in Redis. Subsequent calls with the same arguments return the cached JSON directly, skipping both the query planner and the database entirely.

---

## Why This Was Needed

Commit 34 created the `RedisCacheManager` bean with named caches and per-cache TTLs. That commit deliberately stopped short of annotating any service methods, keeping infrastructure setup and business-layer usage in separate commits. This commit completes the caching story for the most-called read paths:

- `getPostById` — called by every "view post" request; the most frequent single-entity lookup.
- `getPostBySlug` — called for URL-based post navigation (e.g., `/posts/my-first-post`); same frequency as `getPostById`.
- `getAllPosts` — the main listing endpoint; even with pagination, the first page is requested by the overwhelming majority of visitors.
- `getPostsByCategory` — category browsing is a common access pattern; caching per-category first pages reduces DB load during peak browsing.

---

## Decisions Made

### 1. Why only these four methods, not all read methods

**Option A — Cache every public read method**
This would include `getPostsByUser`, `searchPosts`, and `getPostsByTag` in addition to the four chosen methods.

*Rejected because:*
- `searchPosts` uses a two-step process: a GIN index scan for IDs, then a JOIN FETCH for entities. The result depends on full-text ranking scores which change as new posts are indexed. Caching this could serve stale search rankings and would require a complex key that encodes the query string.
- `getPostsByUser` is a user-specific listing. Its results change frequently (every time the user creates or edits a post) and the audience for any given user's list is small. Cache hit rate would be low, and eviction would require tracking per-user keys.
- `getPostsByTag` returns a `List<PostResponse>`, not a `Page`. Its size is unbounded for popular tags. Serializing and deserializing a large list on every cache miss adds latency without a proportional benefit.

**Option B — Cache the four listed methods only (chosen)**
Limit caching to the four methods that are genuinely read-heavy, have stable keys, and serve anonymous or semi-anonymous audiences.

*Chosen because:* Cache effectiveness is highest when hit rate is high and eviction cost is low. The four chosen methods are called by unauthenticated visitors browsing the public site, which is the largest audience. The keys are simple scalars or short composite strings that produce predictable Redis key names.

---

### 2. SpEL key for `getPostById`: `#id` vs `#postId`

The task description showed the key as `#id`, using a parameter variable name `id`. The actual method signature in this codebase is `getPostById(Long postId)` — the parameter is named `postId`, not `id`.

In Spring's SpEL cache key expressions, `#varName` refers to the method parameter by its Java name. If the variable name in the SpEL expression does not match any parameter, Spring evaluates it to `null`. This would produce the Redis key `posts::null` for every call — all calls would map to the same cache entry regardless of which post was requested. This is a silent, hard-to-debug bug.

**Chosen approach:** Use `#postId` to match the actual parameter name in the method signature.

This is the only place where the implementation diverges from the task description's shown annotation. The divergence is intentional and documented here.

---

### 3. `condition = "#page == 0"` on `getAllPosts`

**Option A — Cache all pages**
Remove the condition and cache every page number.

*Rejected because:* Deep pagination requests (page 5, page 10, etc.) are rare. Storing them in Redis wastes memory for cache entries that will almost never be requested again. The TTL is 300 seconds — long enough for these entries to persist while occupying memory unnecessarily.

**Option B — Cache only page 0 (chosen)**
Add `condition = "#page == 0"` so only the first page is ever stored in Redis.

*Chosen because:* The first page (`page == 0`) accounts for the vast majority of listing traffic. Caching it provides the highest hit rate at the lowest memory cost. Deeper pages are still served by the database, which is fast enough for the rare request. This is the approach specified in the task and is sound in practice.

---

### 4. Composite key design for `getAllPosts`

The key `'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir` encodes all four parameters that affect the result.

Because we only cache `#page == 0` (see above), in practice only keys of the form `all_0_<size>_<sortBy>_<sortDir>` are ever stored. The string prefix `all_` prevents key collisions with `getPostsByCategory` entries (which start with `category_`) and with single-post entries (which are plain numeric IDs or slugs).

---

### 5. Why `getPostsByCategory` does not restrict to `page == 0`

`getAllPosts` caches only `page == 0` because it is a global listing across all categories and authors — virtually all visitors land on page 0. `getPostsByCategory` is more targeted: a reader browsing a specific category is more likely to navigate to page 1 or 2 within that category before moving on. Caching all pages within a category is reasonable. Additionally, the number of categories is bounded, so the total number of cache entries (`N_categories * N_pages`) stays manageable.

---

## New Concepts Introduced

### `@Cacheable` — how the proxy intercepts the method call

`@Cacheable` is processed by Spring's caching proxy, which is activated by `@EnableCaching` (done in `RedisCacheConfig` in commit 34). When Spring creates the `PostServiceImpl` bean, it wraps it in a proxy. The proxy intercepts every call to a `@Cacheable`-annotated method before the real method body executes:

```
Caller
  │
  ▼
CacheInterceptor (proxy)
  │
  ├── 1. Evaluate SpEL key expression  →  e.g., "42" for postId=42
  │
  ├── 2. Ask CacheManager for "posts" cache
  │
  ├── 3. Call cache.get("42")
  │       │
  │       ├── HIT:  deserialize JSON from Redis  →  return cached PostResponse
  │       │
  │       └── MISS: call real PostServiceImpl.getPostById(42)
  │                    │
  │                    ▼
  │                 repository query → entity → mapper → PostResponse
  │                    │
  │                    └── cache.put("42", postResponse)  →  store JSON in Redis
  │
  └── Return result to caller
```

The proxy mechanism means the method body is never entered on a cache hit. This is why `@Cacheable` must NOT be placed on `private` methods — proxies only intercept calls that go through the bean reference, not self-calls within the same class.

---

### SpEL (Spring Expression Language) in cache key expressions

The `key` attribute of `@Cacheable` is a SpEL expression evaluated at runtime. SpEL gives access to:

- `#paramName` — the value of the named method parameter
- String literals in single quotes: `'all_'`
- String concatenation with `+`
- Conditionals, method calls, and much more

Examples from this commit:

```java
key = "#postId"
// evaluates to: "42" (for postId=42)

key = "#slug"
// evaluates to: "my-first-post"

key = "'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir"
// evaluates to: "all_0_10_createdAt_desc"

key = "'category_' + #categoryId + '_' + #page + '_' + #size"
// evaluates to: "category_3_0_10"
```

Spring prepends the cache name and a double-colon separator when constructing the actual Redis key:

```
cache name  ::  SpEL result
posts       ::  42
posts       ::  my-first-post
posts       ::  all_0_10_createdAt_desc
posts       ::  category_3_0_10
```

---

### `condition` vs `unless` in `@Cacheable`

`@Cacheable` provides two filtering attributes:

| Attribute | When evaluated | What it controls |
|---|---|---|
| `condition` | Before method executes | Whether caching applies at all for this call |
| `unless` | After method executes | Whether to store the result (can inspect `#result`) |

`condition = "#page == 0"` is evaluated before the method runs. If `page != 0`, the caching proxy does nothing — neither reads from nor writes to Redis. The method executes and returns normally, but the result is discarded from the cache's perspective.

`unless` (not used in this commit) would let you skip caching based on the return value, for example `unless = "#result == null"`. Since the project's `RedisCacheConfig` already calls `disableCachingNullValues()`, a null result is blocked at the cache layer anyway, making `unless = "#result == null"` redundant here.

---

## Files Changed

### `src/main/java/com/studylink/blogcms/service/impl/PostServiceImpl.java`

**Import added:**

```java
import org.springframework.cache.annotation.Cacheable;
```

**`getPostById` — before:**

```java
@Override
public PostResponse getPostById(Long postId) {
```

**After:**

```java
@Override
@Cacheable(value = "posts", key = "#postId")
public PostResponse getPostById(Long postId) {
```

Note: the task specification showed `key = "#id"` but the parameter is named `postId`. Using `#id` would evaluate to `null` in SpEL and cause all cache lookups to collide under the key `posts::null`. The correct expression is `#postId`.

---

**`getPostBySlug` — before:**

```java
@Override
public PostResponse getPostBySlug(String slug) {
```

**After:**

```java
@Override
@Cacheable(value = "posts", key = "#slug")
public PostResponse getPostBySlug(String slug) {
```

---

**`getAllPosts` — before:**

```java
@Override
public Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
```

**After:**

```java
@Override
@Cacheable(value = "posts", key = "'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir", condition = "#page == 0")
public Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
```

---

**`getPostsByCategory` — before:**

```java
@Override
public Page<PostResponse> getPostsByCategory(Long categoryId, int page, int size) {
```

**After:**

```java
@Override
@Cacheable(value = "posts", key = "'category_' + #categoryId + '_' + #page + '_' + #size")
public Page<PostResponse> getPostsByCategory(Long categoryId, int page, int size) {
```

---

## Architecture: what Redis keys look like after these changes

```
Redis keyspace (posts cache)
├── posts::42                         ← getPostById(42)
├── posts::my-first-post              ← getPostBySlug("my-first-post")
├── posts::all_0_10_createdAt_desc    ← getAllPosts(0, 10, "createdAt", "desc")
├── posts::all_0_5_title_asc          ← getAllPosts(0, 5, "title", "asc")
├── posts::category_3_0_10            ← getPostsByCategory(3, 0, 10)
└── posts::category_7_1_10            ← getPostsByCategory(7, 1, 10)
```

---

## What is NOT cached and why

| Method | Reason not cached |
|---|---|
| `searchPosts` | Two-step GIN + JOIN FETCH process; ranking changes as content is indexed; query strings are too varied to have a high cache hit rate |
| `getPostsByUser` | User-owned content changes frequently; audience per user-list is small; eviction would require per-user key tracking |
| `getPostsByTag` | Returns an unbounded `List<>`; popular tags could produce very large serialized values |
| All write methods (`createPost`, `updatePost`, etc.) | Write methods must not be cached; `@Cacheable` is for reads only |

---

## Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Compilation is clean | `./mvnw compile -q` | Exit 0, no output |
| Cache miss → DB hit | Start app with Redis; call `GET /api/v1/posts/1`; check application logs | Log shows Hibernate SQL query for post id=1 |
| Cache hit → no DB hit | Call `GET /api/v1/posts/1` a second time; check logs | No SQL query logged; response is identical and faster |
| Redis key exists after first call | `redis-cli KEYS "posts::*"` | Key `posts::1` is present |
| Key format is correct | `redis-cli GET "posts::1"` | Human-readable JSON starting with class name metadata, containing post fields |
| TTL applied | `redis-cli TTL "posts::1"` | Returns a value between 1 and 300 (the posts TTL configured in commit 34) |
| Slug-based cache | Call `GET /api/v1/posts/slug/my-first-post` twice | Second call has no SQL log; `redis-cli KEYS "posts::*"` shows `posts::my-first-post` |
| Listing cache — page 0 is cached | Call `GET /api/v1/posts?page=0&size=10&sortBy=createdAt&sortDir=desc` twice | Second call has no SQL; `redis-cli KEYS "posts::all*"` shows `posts::all_0_10_createdAt_desc` |
| Listing cache — page 1 is NOT cached | Call `GET /api/v1/posts?page=1&size=10&sortBy=createdAt&sortDir=desc` twice | Both calls show SQL log; `redis-cli KEYS "posts::all*"` shows no key for page 1 |
| Category cache | Call `GET /api/v1/posts/category/3?page=0&size=10` twice | Second call has no SQL; key `posts::category_3_0_10` exists in Redis |
| SpEL key correctness | `redis-cli KEYS "posts::null"` | Returns empty list — confirms `#postId` resolves correctly, no null-key collision |
