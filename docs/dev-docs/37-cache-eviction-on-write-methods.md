# Commit 36 — @CacheEvict on PostServiceImpl Write Methods

## Overview

This commit closes the cache consistency gap introduced in commit 35. That commit added `@Cacheable` to four read methods in `PostServiceImpl`, which means Redis can serve stale data after any mutation. This commit adds `@CacheEvict(value = "posts", allEntries = true)` to every write method — `createPost`, `updatePost`, `deletePost`, `publishPost`, and `uploadFeaturedImage` — so that the entire `posts` cache is flushed whenever the underlying data changes. The next read after any mutation will always hit the database and repopulate the cache with fresh data.

---

## Why This Was Needed

Redis stores a snapshot of query results. Once a snapshot is stored it does not update automatically when the database changes. Without eviction, the following bugs occur:

- A user creates a new post. The listing cache (`posts::all_0_10_createdAt_desc`) still shows the old list — the new post is invisible to readers until TTL expires (up to 300 seconds).
- A user updates a post's title. The detail cache (`posts::42`) returns the old title.
- A user deletes a post. The listing cache still contains the deleted post. Readers see a post that no longer exists.
- A DRAFT post is published. The listing cache does not include it because the publish event did not trigger a refresh.
- An author replaces the featured image. The detail cache still shows the old image URL.

In each case, readers would see stale data for up to the full cache TTL (300 seconds for the `posts` cache). For a CMS, this is unacceptable — authors expect their changes to be visible immediately.

---

## Decisions Made

### 1. `allEntries = true` vs. targeted key eviction

This is the central design decision of this commit.

**Option A — Targeted eviction per write method**

Evict only the specific cache entries affected by each mutation:
- `createPost`: evict all listing keys (`all_*`, `category_*`)
- `updatePost`: evict `#postId`, the old slug, the new slug (if changed), the old category listing, and the new category listing
- `deletePost`: evict `#postId`, the post's slug, its category listing
- `publishPost`: evict `#postId`, its slug, all listing keys
- `uploadFeaturedImage`: evict only `#postId` and the post's slug

*Rejected because:*

- The slug can change during `updatePost`. The method receives only the new title — the old slug is not a method parameter. To evict `posts::<old-slug>` you would need to load the post first, capture the slug before mutation, then evict it — but `@CacheEvict` key expressions only see the method parameters and return value, not intermediate local variables. A workaround (evicting by loading the entity in a separate step) would require custom `CacheManager` calls in the service body, violating the clean annotation-driven caching convention the project uses.
- The category for a post can change during `updatePost`. If a post moves from category 3 to category 7, you need to evict both `posts::category_3_*` and `posts::category_7_*`. Neither the old nor the new category ID is reliably available as a method parameter without pre-loading the entity.
- Tracking all permutations of listing keys (`all_0_*`, `all_0_5_*`, `category_*_0_*`, etc.) is fragile. Every new listing method added in the future must update the eviction logic on every write method. This is a maintenance hazard.

**Option B — `allEntries = true` on all write methods (chosen)**

Add a single `@CacheEvict(value = "posts", allEntries = true)` to each write method. This evicts every key in the `posts` cache whenever any mutation occurs.

*Chosen because:*

- It is correct in all cases, regardless of which fields changed.
- It requires no knowledge of what keys currently exist in Redis.
- It is future-proof: adding a new `@Cacheable` read method requires no corresponding change to the write methods.
- The `posts` cache is populated on demand (next read repopulates it), so the only cost of over-eviction is one extra database query per cache miss — which is exactly what happened before caching was introduced.
- A CMS write operation (create, update, publish) is inherently a low-frequency event compared to reads. The performance cost of evicting the whole cache on every write is negligible in practice.

---

### 2. `incrementViewCount` — no eviction

`incrementViewCount` is called on every single page view. It is the most frequent write operation in the entire system, firing once per user per post visit.

If we evicted the cache on every view count increment, the cache would be invalidated thousands of times per hour for popular posts, making it effectively useless. The `@Cacheable` on `getPostById` and `getPostBySlug` would serve no purpose.

View count is also a metric that readers implicitly understand to be approximate — no reader expects the count to reflect changes made in the last five minutes. A 300-second lag is acceptable.

*Decision:* `incrementViewCount` receives no `@CacheEvict` annotation. The cached `PostResponse` will show a view count that is up to one TTL period behind the database. This is an intentional trade-off.

---

### 3. `@CachePut` vs `@CacheEvict` on `updatePost` and `publishPost`

`@CachePut` writes the return value of the method into the cache, making it an alternative to eviction for single-entity updates: instead of evicting and waiting for the next read to repopulate, you update the cache immediately with the fresh value.

**Option — Use `@CachePut` on `updatePost` and `publishPost`**

```java
@CachePut(value = "posts", key = "#postId")
public PostResponse updatePost(Long postId, UpdatePostRequest request, Long userId) { ... }
```

*Rejected because:*

- `@CachePut` can only update the entry for a single key. The updated post appears in multiple cache entries: its own ID key, its own slug key (which may have changed), and one or more listing keys (`all_*`, `category_*`). `@CachePut` cannot update listing pages — these are serialized `Page<PostResponse>` objects containing multiple posts. There is no way to splice a single updated `PostResponse` into a cached page without loading and re-serializing the entire page.
- Using `@CachePut` for the single-entity entry alongside `@CacheEvict(allEntries = true)` for the listing entries would leave the old slug key alive in Redis (since `@CachePut` writes a new key but does not delete the old one). This creates a permanently stale slug entry that is never evicted until TTL.
- The operational simplicity of a single `@CacheEvict(allEntries = true)` on every write method outweighs the marginal benefit of immediately repopulating one key.

*Decision:* Use `@CacheEvict(allEntries = true)` on all write methods. Do not use `@CachePut`.

---

## New Concepts Introduced

### `@CacheEvict` — how it works

`@CacheEvict` is the Spring caching annotation that tells the caching proxy to remove one or more entries from a named cache when the annotated method is called. Like `@Cacheable`, it is processed by a proxy that wraps the service bean.

The proxy intercepts the method call in this order:

```
Caller
  │
  ▼
CacheInterceptor (proxy)
  │
  ├── 1. Before method (if beforeInvocation = true, which is NOT the default)
  │
  ├── 2. Call real PostServiceImpl.createPost(...)
  │       └── business logic executes, post is saved to DB
  │
  └── 3. After method returns successfully (default behaviour)
          └── evict ALL entries from the "posts" cache in Redis
```

Key points about the default behaviour:

- Eviction happens **after** the method returns, not before. This ensures the database write is committed before the cache is cleared. If the method throws an exception (e.g., the repository save fails), the cache is NOT evicted — it stays consistent with the database.
- `allEntries = true` tells the cache manager to call `cache.clear()` on the named cache rather than evicting a specific key. Every key in the `posts` Redis namespace is deleted in a single operation.
- `beforeInvocation = true` (not used here) would evict before the method runs, which guarantees eviction even on exceptions. This is useful in rare scenarios but dangerous here because it could leave the cache empty if the DB write subsequently fails.

---

### `allEntries = true` — what it does in Redis

When `allEntries = true` is used with the `RedisCacheManager` configured in commit 34, Spring calls `RedisCache.clear()`, which executes a `SCAN` + `DEL` pattern against Redis for all keys matching `posts::*`. Every key in the `posts` cache namespace is removed in one operation regardless of how many there are.

This is equivalent to:

```
redis-cli --scan --pattern "posts::*" | xargs redis-cli DEL
```

Because the Redis `SCAN` operation is non-blocking (it iterates the keyspace in small chunks), this is safe even on large datasets. It does not lock Redis.

---

### Why `@CacheEvict` and `@Transactional` coexist safely

The write methods already carry `@Transactional`. Adding `@CacheEvict` means two proxies wrap the call: the transaction proxy and the caching proxy. Spring applies them in a defined order:

```
Caller
  │
  ▼
CachingProxy  ← outermost (by default; Spring orders AOP advisors)
  │
  ▼
TransactionProxy
  │
  ▼
Real method
```

The eviction (step 3 above) fires after the method returns but before the caching proxy finishes. The transaction is committed inside the transaction proxy before returning to the caching proxy. The sequence is:

1. Caching proxy intercepts call
2. Transaction proxy begins transaction
3. Real method body executes (DB writes buffered in transaction)
4. Transaction proxy commits to PostgreSQL
5. Caching proxy evicts Redis entries

This ordering means that by the time Redis is cleared, the new data is already durable in PostgreSQL. The next cache miss will always retrieve the committed data.

---

## Files Changed

### `src/main/java/com/studylink/blogcms/service/impl/PostServiceImpl.java`

**Import added:**

```java
import org.springframework.cache.annotation.CacheEvict;
```

**`createPost` — before:**

```java
@Override
@Transactional
public PostResponse createPost(CreatePostRequest request, Long userId) {
```

**After:**

```java
@Override
@Transactional
@CacheEvict(value = "posts", allEntries = true)
public PostResponse createPost(CreatePostRequest request, Long userId) {
```

---

**`updatePost` — before:**

```java
@Override
@Transactional
public PostResponse updatePost(Long postId, UpdatePostRequest request, Long userId) {
```

**After:**

```java
@Override
@Transactional
@CacheEvict(value = "posts", allEntries = true)
public PostResponse updatePost(Long postId, UpdatePostRequest request, Long userId) {
```

---

**`deletePost` — before:**

```java
@Override
@Transactional
public void deletePost(Long postId, Long userId) {
```

**After:**

```java
@Override
@Transactional
@CacheEvict(value = "posts", allEntries = true)
public void deletePost(Long postId, Long userId) {
```

---

**`publishPost` — before:**

```java
@Override
@Transactional
public PostResponse publishPost(Long postId, Long userId) {
```

**After:**

```java
@Override
@Transactional
@CacheEvict(value = "posts", allEntries = true)
public PostResponse publishPost(Long postId, Long userId) {
```

---

**`uploadFeaturedImage` — before:**

```java
@Override
@Transactional
public PostResponse uploadFeaturedImage(Long postId, MultipartFile file, Long userId) {
```

**After:**

```java
@Override
@Transactional
@CacheEvict(value = "posts", allEntries = true)
public PostResponse uploadFeaturedImage(Long postId, MultipartFile file, Long userId) {
```

---

**`incrementViewCount` — unchanged (no annotation added):**

```java
@Override
@Transactional
public void incrementViewCount(Long postId) {
    // Check if post exists
    if (!postRepository.existsById(postId)) {
        throw new ResourceNotFoundException("Post", "id", postId);
    }
    // Atomic increment - no N+1 problem here!
    postRepository.incrementViewCount(postId);
}
```

View count staleness of up to one TTL period (300 seconds) is acceptable. Adding eviction here would defeat the cache entirely on high-traffic posts.

---

## Complete cache behaviour after this commit

```
Write operation          Cache behaviour
─────────────────────────────────────────────────────────────
createPost               Evicts all posts::* keys
updatePost               Evicts all posts::* keys
deletePost               Evicts all posts::* keys
publishPost              Evicts all posts::* keys
uploadFeaturedImage      Evicts all posts::* keys
incrementViewCount       No eviction (intentional; view count staleness accepted)

Read operation           Cache behaviour
─────────────────────────────────────────────────────────────
getPostById              Cache miss → DB → store posts::<id>
getPostBySlug            Cache miss → DB → store posts::<slug>
getAllPosts (page 0)      Cache miss → DB → store posts::all_0_<size>_<sort>
getPostsByCategory       Cache miss → DB → store posts::category_<id>_<page>_<size>
getPostsByUser           Not cached (unchanged from commit 35)
searchPosts              Not cached (unchanged from commit 35)
getPostsByTag            Not cached (unchanged from commit 35)
```

---

## Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Compilation is clean | `./mvnw compile -q` | Exit 0, no output |
| Cache is populated after first read | `GET /api/v1/posts/1`; then `redis-cli KEYS "posts::*"` | Key `posts::1` is present |
| Cache is evicted after update | `PUT /api/v1/posts/1` with any change; then `redis-cli KEYS "posts::*"` | No `posts::*` keys remain |
| Cache repopulates after update | `GET /api/v1/posts/1` after update; check logs | Hibernate SQL is logged (cache miss); key `posts::1` reappears |
| Listing cache is evicted after create | `GET /api/v1/posts?page=0&size=10&...` (cache listing); then `POST /api/v1/posts`; then `redis-cli KEYS "posts::all*"` | All listing keys gone after create |
| Delete evicts cache | `DELETE /api/v1/posts/1`; then `redis-cli KEYS "posts::*"` | No `posts::*` keys remain |
| Publish evicts cache | `POST /api/v1/posts/1/publish`; then `redis-cli KEYS "posts::*"` | No `posts::*` keys remain |
| Image upload evicts cache | `POST /api/v1/posts/1/image` (multipart); then `redis-cli KEYS "posts::*"` | No `posts::*` keys remain |
| View count does NOT evict | `POST /api/v1/posts/1/view` repeatedly; check `redis-cli KEYS "posts::*"` | Keys remain present; view count in DB increments via `redis-cli GET "posts::1"` shows old count |
| No stale deleted post served | Delete post 1; then `GET /api/v1/posts/1` | Returns 404 (cache was cleared; DB returns deleted record; service throws ResourceNotFoundException) |
| Slug staleness eliminated | Update post 1 title (slug changes); then `GET /api/v1/posts/slug/old-slug` | Returns 404 — old slug key was evicted; DB has no post with old slug |
