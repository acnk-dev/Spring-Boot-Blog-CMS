# Commit 38 — Fix PageImpl Redis Serialization and Cache Warming Sort Key

## Overview

This commit fixes two bugs that were discovered after Redis caching was activated on
`PostServiceImpl`:

1. **`PageImpl` deserialization crash** — any endpoint that returned a cached `Page<T>`
   would throw a `SerializationException` on the second request (cache hit), making
   caching completely broken for paginated responses.
2. **Wrong sort field in cache warming** — `CacheWarmingConfig` was pre-loading posts
   sorted by `createdAt`, but real API requests sort by `publishedAt`, so the warmed
   cache entry was never actually used.

---

## Bug 1 — PageImpl Cannot Be Deserialized from Redis

### What went wrong

After adding `@Cacheable` to `getAllPosts` and `getPostsByCategory`, the sequence of
events for a cached request was:

```
Request 1 (cache miss):
  → Spring calls the service method
  → method returns Page<PostResponse>  (a PageImpl internally)
  → Spring serializes it to JSON and writes it to Redis  ✓

Request 2 (cache hit):
  → Spring reads the JSON bytes from Redis
  → Jackson tries to deserialize them back into PageImpl
  → CRASH: "Cannot construct instance of PageImpl — no Creators exist"  ✗
```

The error in full:

```
org.springframework.data.redis.serializer.SerializationException: Could not read JSON:
Cannot construct instance of `org.springframework.data.domain.PageImpl`
(no Creators, like default constructor, exist): cannot deserialize from Object value
(no delegate- or property-based Creator)
```

### Why PageImpl fails

Jackson needs at least one of the following to construct an object:

| Strategy | What it requires |
|---|---|
| Default constructor | A no-arg `public PageImpl()` — does not exist |
| `@JsonCreator` method/constructor | An annotated factory — not present on `PageImpl` |
| Delegating creator | A single-argument constructor annotated with `@JsonCreator` |

Spring's `PageImpl` has none of these. It was designed for serialization (writing to JSON)
but not for deserialization (reading back from JSON). This is a known limitation of using
`Page<T>` as a cache value type.

The write succeeds because Jackson can read the fields via getters. The read fails because
Jackson cannot call a constructor to build the object.

### The fix — RestPage\<T\>

We created a new class `RestPage<T>` that:

- **Extends `PageImpl<T>`** — so it IS-A `Page<T>`. No changes needed in controllers or
  callers that declare `Page<T>` as the type.
- **Adds `@JsonCreator`** — a constructor annotated with `@JsonCreator(mode = PROPERTIES)`
  that tells Jackson exactly which JSON fields map to which constructor parameters.
- **Adds `@JsonIgnoreProperties(ignoreUnknown = true)`** — ignores pagination metadata
  fields that Jackson cannot map (`pageable`, `sort`, etc.), preventing deserialization
  errors when Spring adds new fields to `PageImpl` in future versions.

```java
// dto/response/RestPage.java

@JsonIgnoreProperties(ignoreUnknown = true)
public class RestPage<T> extends PageImpl<T> {

    // This is the constructor Jackson uses when reading back from Redis.
    // @JsonCreator(mode = PROPERTIES) means "match each @JsonProperty
    // to the field of the same name in the JSON object".
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public RestPage(
            @JsonProperty("content") List<T> content,    // the items on this page
            @JsonProperty("number") int number,           // page index (0-based)
            @JsonProperty("size") int size,               // requested page size
            @JsonProperty("totalElements") long totalElements) { // total across all pages
        super(content, PageRequest.of(number, Math.max(size, 1)), totalElements);
        //                                   ^^^^^^^^^^^^^^^^^^^
        // Math.max guards against size=0, which PageRequest rejects with an exception.
    }

    // This constructor is used when wrapping a Page<T> returned by the repository.
    public RestPage(Page<T> page) {
        super(page.getContent(), page.getPageable(), page.getTotalElements());
    }
}
```

### What @JsonCreator actually does

`@JsonCreator` is a Jackson annotation that designates a constructor (or static factory
method) as the entry point for deserialization. Without it, Jackson looks for a no-arg
constructor and then uses setter injection — neither of which works for `PageImpl`.

`mode = JsonCreator.Mode.PROPERTIES` means each parameter is bound by name:

```json
{
  "content": [...],
  "number": 0,
  "size": 20,
  "totalElements": 47
}
```

Jackson matches `"content"` → `@JsonProperty("content")` parameter, and so on.

`@JsonProperty` on each parameter tells Jackson the exact JSON key to bind. Without it,
parameter names are not preserved in compiled bytecode (unless `-parameters` compiler flag
is set), so Jackson would not know which JSON field maps to which argument.

### Changes to PostService and PostServiceImpl

The two cached methods changed their return type from `Page<PostResponse>` to
`RestPage<PostResponse>`, and their return statements wrap the result:

```java
// BEFORE
public Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
    ...
    return posts.map(postMapper::toResponse);
}

// AFTER
public RestPage<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
    ...
    return new RestPage<>(posts.map(postMapper::toResponse));
    //     ^^^^^^^^^^^^ wraps the PageImpl in a Jackson-deserializable subclass
}
```

`posts.map(postMapper::toResponse)` still returns a `PageImpl<PostResponse>`. The `new
RestPage<>(page)` convenience constructor delegates to `super(page.getContent(),
page.getPageable(), page.getTotalElements())` — same data, different runtime type.

Because `RestPage extends PageImpl implements Page`, the controller method:

```java
public ResponseEntity<Page<PostResponse>> getAllPosts(...) {
    Page<PostResponse> posts = postService.getAllPosts(...); // RestPage IS-A Page — fine
    return ResponseEntity.ok(posts);
}
```

...requires no change. Java's type system handles the upcast transparently.

The same fix was applied to `getPostsByCategory`, which was also caching a `Page<T>` and
would have hit the exact same crash.

---

## Bug 2 — Cache Warming Used the Wrong Sort Field

### What went wrong

`CacheWarmingConfig.warmPostsCache()` called:

```java
postService.getAllPosts(0, 20, "createdAt", "desc");
```

The `@Cacheable` key on `getAllPosts` is:

```java
key = "'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir"
```

So this warming call populated Redis key:

```
posts::all_0_20_createdAt_desc
```

But the `PostController` default sort is `publishedAt`:

```java
@GetMapping
public ResponseEntity<Page<PostResponse>> getAllPosts(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "publishedAt") String sortBy,   // <-- here
        @RequestParam(defaultValue = "desc") String sortDir) {
```

So real API requests looked up key `posts::all_0_20_publishedAt_desc` — a permanent cache
miss. Every request hit the database. The warmed entry was never used.

### The fix

One character change in `CacheWarmingConfig`:

```java
// BEFORE
postService.getAllPosts(0, 20, "createdAt", "desc");

// AFTER
postService.getAllPosts(0, 20, "publishedAt", "desc");
```

The warming call now produces key `posts::all_0_20_publishedAt_desc`, which is exactly
what the controller looks up on every default request.

### Lesson: cache key inputs must match caller inputs exactly

When `@Cacheable` uses method parameters as part of the key, **every caller** (including
warming code, tests, and other services) must pass the same values to hit the same cache
entry. A mismatch in any parameter silently creates a separate cache entry and bypasses the
intended cache entirely — no error, just wasted Redis memory and a database hit every time.

---

## Files Changed

| File | What changed |
|---|---|
| `dto/response/RestPage.java` | **New file.** Jackson-deserializable `PageImpl` wrapper with `@JsonCreator`. |
| `service/PostService.java` | `getAllPosts` and `getPostsByCategory` return type changed to `RestPage<PostResponse>`. Added `RestPage` import. |
| `service/impl/PostServiceImpl.java` | Same two methods return `new RestPage<>(...)`. Added `RestPage` import. |
| `config/CacheWarmingConfig.java` | Sort field changed from `"createdAt"` to `"publishedAt"`. |

---

## Concepts Introduced

### Why `@JsonCreator` is needed for classes without a default constructor

Jackson's default deserialization strategy:

1. Call the no-arg constructor to create an empty instance.
2. Call setters (or set fields directly) for each JSON property.

If there is no no-arg constructor, step 1 fails immediately. `@JsonCreator` is the escape
hatch: it tells Jackson "use this constructor or factory method instead of the default
strategy." With `mode = PROPERTIES`, Jackson reads the entire JSON object first, then calls
the constructor with all the values at once — like a builder pattern.

### Why @JsonIgnoreProperties(ignoreUnknown = true) is important

`PageImpl` serializes to JSON with many fields: `content`, `number`, `size`,
`totalElements`, `totalPages`, `first`, `last`, `empty`, `sort`, `pageable`, and more. Our
`@JsonCreator` constructor only declares four parameters. Without
`@JsonIgnoreProperties(ignoreUnknown = true)`, Jackson would throw an
`UnrecognizedPropertyException` for every field it cannot map to a constructor parameter.
The annotation instructs Jackson to silently skip fields it has no binding for.

### Why the controller does not need to change

Java polymorphism: `RestPage<T>` extends `PageImpl<T>` which im# Commit 38 — Fix PageImpl Redis Serialization and Cache Warming Sort Key

## Overview

This commit fixes two bugs that were discovered after Redis caching was activated on
`PostServiceImpl`:

1. **`PageImpl` deserialization crash** — any endpoint that returned a cached `Page<T>`
   would throw a `SerializationException` on the second request (cache hit), making
   caching completely broken for paginated responses.
2. **Wrong sort field in cache warming** — `CacheWarmingConfig` was pre-loading posts
   sorted by `createdAt`, but real API requests sort by `publishedAt`, so the warmed
   cache entry was never actually used.

---

## Bug 1 — PageImpl Cannot Be Deserialized from Redis

### What went wrong

After adding `@Cacheable` to `getAllPosts` and `getPostsByCategory`, the sequence of
events for a cached request was:

```
Request 1 (cache miss):
  → Spring calls the service method
  → method returns Page<PostResponse>  (a PageImpl internally)
  → Spring serializes it to JSON and writes it to Redis  ✓

Request 2 (cache hit):
  → Spring reads the JSON bytes from Redis
  → Jackson tries to deserialize them back into PageImpl
  → CRASH: "Cannot construct instance of PageImpl — no Creators exist"  ✗
```

The error in full:

```
org.springframework.data.redis.serializer.SerializationException: Could not read JSON:
Cannot construct instance of `org.springframework.data.domain.PageImpl`
(no Creators, like default constructor, exist): cannot deserialize from Object value
(no delegate- or property-based Creator)
```

### Why PageImpl fails

Jackson needs at least one of the following to construct an object:

| Strategy | What it requires |
|---|---|
| Default constructor | A no-arg `public PageImpl()` — does not exist |
| `@JsonCreator` method/constructor | An annotated factory — not present on `PageImpl` |
| Delegating creator | A single-argument constructor annotated with `@JsonCreator` |

Spring's `PageImpl` has none of these. It was designed for serialization (writing to JSON)
but not for deserialization (reading back from JSON). This is a known limitation of using
`Page<T>` as a cache value type.

The write succeeds because Jackson can read the fields via getters. The read fails because
Jackson cannot call a constructor to build the object.

### The fix — RestPage\<T\>

We created a new class `RestPage<T>` that:

- **Extends `PageImpl<T>`** — so it IS-A `Page<T>`. No changes needed in controllers or
  callers that declare `Page<T>` as the type.
- **Adds `@JsonCreator`** — a constructor annotated with `@JsonCreator(mode = PROPERTIES)`
  that tells Jackson exactly which JSON fields map to which constructor parameters.
- **Adds `@JsonIgnoreProperties(ignoreUnknown = true)`** — ignores pagination metadata
  fields that Jackson cannot map (`pageable`, `sort`, etc.), preventing deserialization
  errors when Spring adds new fields to `PageImpl` in future versions.

```java
// dto/response/RestPage.java

@JsonIgnoreProperties(ignoreUnknown = true)
public class RestPage<T> extends PageImpl<T> {

    // This is the constructor Jackson uses when reading back from Redis.
    // @JsonCreator(mode = PROPERTIES) means "match each @JsonProperty
    // to the field of the same name in the JSON object".
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public RestPage(
            @JsonProperty("content") List<T> content,    // the items on this page
            @JsonProperty("number") int number,           // page index (0-based)
            @JsonProperty("size") int size,               // requested page size
            @JsonProperty("totalElements") long totalElements) { // total across all pages
        super(content, PageRequest.of(number, Math.max(size, 1)), totalElements);
        //                                   ^^^^^^^^^^^^^^^^^^^
        // Math.max guards against size=0, which PageRequest rejects with an exception.
    }

    // This constructor is used when wrapping a Page<T> returned by the repository.
    public RestPage(Page<T> page) {
        super(page.getContent(), page.getPageable(), page.getTotalElements());
    }
}
```

### What @JsonCreator actually does

`@JsonCreator` is a Jackson annotation that designates a constructor (or static factory
method) as the entry point for deserialization. Without it, Jackson looks for a no-arg
constructor and then uses setter injection — neither of which works for `PageImpl`.

`mode = JsonCreator.Mode.PROPERTIES` means each parameter is bound by name:

```json
{
  "content": [...],
  "number": 0,
  "size": 20,
  "totalElements": 47
}
```

Jackson matches `"content"` → `@JsonProperty("content")` parameter, and so on.

`@JsonProperty` on each parameter tells Jackson the exact JSON key to bind. Without it,
parameter names are not preserved in compiled bytecode (unless `-parameters` compiler flag
is set), so Jackson would not know which JSON field maps to which argument.

### Changes to PostService and PostServiceImpl

The two cached methods changed their return type from `Page<PostResponse>` to
`RestPage<PostResponse>`, and their return statements wrap the result:

```java
// BEFORE
public Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
    ...
    return posts.map(postMapper::toResponse);
}

// AFTER
public RestPage<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
    ...
    return new RestPage<>(posts.map(postMapper::toResponse));
    //     ^^^^^^^^^^^^ wraps the PageImpl in a Jackson-deserializable subclass
}
```

`posts.map(postMapper::toResponse)` still returns a `PageImpl<PostResponse>`. The `new
RestPage<>(page)` convenience constructor delegates to `super(page.getContent(),
page.getPageable(), page.getTotalElements())` — same data, different runtime type.

Because `RestPage extends PageImpl implements Page`, the controller method:

```java
public ResponseEntity<Page<PostResponse>> getAllPosts(...) {
    Page<PostResponse> posts = postService.getAllPosts(...); // RestPage IS-A Page — fine
    return ResponseEntity.ok(posts);
}
```

...requires no change. Java's type system handles the upcast transparently.

The same fix was applied to `getPostsByCategory`, which was also caching a `Page<T>` and
would have hit the exact same crash.

---

## Bug 2 — Cache Warming Used the Wrong Sort Field

### What went wrong

`CacheWarmingConfig.warmPostsCache()` called:

```java
postService.getAllPosts(0, 20, "createdAt", "desc");
```

The `@Cacheable` key on `getAllPosts` is:

```java
key = "'all_' + #page + '_' + #size + '_' + #sortBy + '_' + #sortDir"
```

So this warming call populated Redis key:

```
posts::all_0_20_createdAt_desc
```

But the `PostController` default sort is `publishedAt`:

```java
@GetMapping
public ResponseEntity<Page<PostResponse>> getAllPosts(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "publishedAt") String sortBy,   // <-- here
        @RequestParam(defaultValue = "desc") String sortDir) {
```

So real API requests looked up key `posts::all_0_20_publishedAt_desc` — a permanent cache
miss. Every request hit the database. The warmed entry was never used.

### The fix

One character change in `CacheWarmingConfig`:

```java
// BEFORE
postService.getAllPosts(0, 20, "createdAt", "desc");

// AFTER
postService.getAllPosts(0, 20, "publishedAt", "desc");
```

The warming call now produces key `posts::all_0_20_publishedAt_desc`, which is exactly
what the controller looks up on every default request.

### Lesson: cache key inputs must match caller inputs exactly

When `@Cacheable` uses method parameters as part of the key, **every caller** (including
warming code, tests, and other services) must pass the same values to hit the same cache
entry. A mismatch in any parameter silently creates a separate cache entry and bypasses the
intended cache entirely — no error, just wasted Redis memory and a database hit every time.

---

## Files Changed

| File | What changed |
|---|---|
| `dto/response/RestPage.java` | **New file.** Jackson-deserializable `PageImpl` wrapper with `@JsonCreator`. |
| `service/PostService.java` | `getAllPosts` and `getPostsByCategory` return type changed to `RestPage<PostResponse>`. Added `RestPage` import. |
| `service/impl/PostServiceImpl.java` | Same two methods return `new RestPage<>(...)`. Added `RestPage` import. |
| `config/CacheWarmingConfig.java` | Sort field changed from `"createdAt"` to `"publishedAt"`. |

---

## Concepts Introduced

### Why `@JsonCreator` is needed for classes without a default constructor

Jackson's default deserialization strategy:

1. Call the no-arg constructor to create an empty instance.
2. Call setters (or set fields directly) for each JSON property.

If there is no no-arg constructor, step 1 fails immediately. `@JsonCreator` is the escape
hatch: it tells Jackson "use this constructor or factory method instead of the default
strategy." With `mode = PROPERTIES`, Jackson reads the entire JSON object first, then calls
the constructor with all the values at once — like a builder pattern.

### Why @JsonIgnoreProperties(ignoreUnknown = true) is important

`PageImpl` serializes to JSON with many fields: `content`, `number`, `size`,
`totalElements`, `totalPages`, `first`, `last`, `empty`, `sort`, `pageable`, and more. Our
`@JsonCreator` constructor only declares four parameters. Without
`@JsonIgnoreProperties(ignoreUnknown = true)`, Jackson would throw an
`UnrecognizedPropertyException` for every field it cannot map to a constructor parameter.
The annotation instructs Jackson to silently skip fields it has no binding for.

### Why the controller does not need to change

Java polymorphism: `RestPage<T>` extends `PageImpl<T>` which implements `Page<T>`. A
`RestPage<PostResponse>` can be assigned to a `Page<PostResponse>` variable without a
cast. When Jackson serializes the `ResponseEntity<Page<PostResponse>>` for the HTTP
response, it sees the actual runtime type (`RestPage`) and serializes all its fields
correctly — because `RestPage` inherits all the getters from `PageImpl`.

---

## Testing Checklist

- [ ] `GET /api/posts` — first request hits DB (cache miss log appears), second request is
      served from cache (no DB query in logs).
- [ ] `GET /api/posts` again — no `SerializationException` in logs.
- [ ] `GET /api/posts?category=1` — same two-request test for `getPostsByCategory`.
- [ ] Restart the application — check logs for "Cache warming: first page of posts loaded"
      with no warning lines.
- [ ] After restart, `GET /api/posts` immediately — should be a cache hit (warmed by
      `CacheWarmingConfig`).
- [ ] In `redis-cli`: `KEYS posts::*` — should show `posts::all_0_20_publishedAt_desc`
      after warming, not `posts::all_0_20_createdAt_desc`.
- [ ] Response shape of `GET /api/posts` is unchanged — `content`, `totalElements`,
      `totalPages`, `number`, `size` all present.
plements `Page<T>`. A
`RestPage<PostResponse>` can be assigned to a `Page<PostResponse>` variable without a
cast. When Jackson serializes the `ResponseEntity<Page<PostResponse>>` for the HTTP
response, it sees the actual runtime type (`RestPage`) and serializes all its fields
correctly — because `RestPage` inherits all the getters from `PageImpl`.

---

## Testing Checklist

- [ ] `GET /api/posts` — first request hits DB (cache miss log appears), second request is
  served from cache (no DB query in logs).
- [ ] `GET /api/posts` again — no `SerializationException` in logs.
- [ ] `GET /api/posts?category=1` — same two-request test for `getPostsByCategory`.
- [ ] Restart the application — check logs for "Cache warming: first page of posts loaded"
  with no warning lines.
- [ ] After restart, `GET /api/posts` immediately — should be a cache hit (warmed by
  `CacheWarmingConfig`).
- [ ] In `redis-cli`: `KEYS posts::*` — should show `posts::all_0_20_publishedAt_desc`
  after warming, not `posts::all_0_20_createdAt_desc`.
- [ ] Response shape of `GET /api/posts` is unchanged — `content`, `totalElements`,
  `totalPages`, `number`, `size` all present.
