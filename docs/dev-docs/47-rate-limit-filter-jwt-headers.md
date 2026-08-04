# Commit 46 — Rate-limit response headers (`X-RateLimit-Remaining` + `Retry-After`)

## 1. Overview

This commit enhances the existing `RateLimitFilter` with two HTTP response headers that
tell callers how many tokens they have left in their bucket, and — when the bucket is
empty — how long they must wait before trying again. No new files were created; the
change is confined to a small section of one existing filter class.

The filter already handled JWT-based user extraction, role-based bucket selection, and
JSON 429 responses. What was missing was the standard HTTP vocabulary that lets API
clients (mobile apps, third-party integrations, automated test suites) react to rate
limits programmatically instead of parsing the response body.

---

## 2. Why this was needed

Without response headers a client has no way to know:

- How many requests it can still make before hitting the wall.
- How long it must back off when it does hit the wall.

The client's only option is to keep sending requests and watch for 429s — an
inefficient strategy that wastes quota and pollutes logs. Adding `X-RateLimit-Remaining`
and `Retry-After` follows the HTTP convention established by RFC 6585 (`Retry-After`)
and widely adopted by APIs such as GitHub, Stripe, and Twitter.

---

## 3. Decisions made

### 3.1 When to call `getAvailableTokens()`

`bucket.tryConsume(1)` atomically removes one token and returns `true` if the bucket had
at least one token, `false` otherwise. After a successful consume, calling
`bucket.getAvailableTokens()` reads the current count from Redis.

**Option A — call `getAvailableTokens()` before `tryConsume`.**
Rejected because: the value would be stale by the time the response is written if
another thread or pod consumed a token in between.

**Option B — call `getAvailableTokens()` after `tryConsume` (chosen).**
This is still an approximation in a concurrent system — another request on a
different pod could consume a token in the microseconds between our consume and our
read — but it is accurate enough for client guidance. This is the approach used by
every major rate-limiting library; the Bucket4j documentation recommends it explicitly.

### 3.2 `Retry-After` value

**Option A — compute exact seconds until the next token refill.**
Bucket4j exposes `bucket.estimateAbilityToConsume(1)` which returns a probe with
`nanosToWaitForRefill()`. This would give a precise countdown but requires an extra
round-trip to Redis and adds complexity for marginal benefit.

**Option B — fixed 3600 seconds (chosen).**
Both SUBSCRIBER and AUTHOR buckets use a 1-hour refill window. Returning 3600 is
conservative (the client might actually be able to retry sooner if it was close to the
end of a refill interval) but simple, predictable, and correct in the worst case.
For the ADMIN bucket this value is irrelevant in practice because the capacity is
`Long.MAX_VALUE / 2`.

### 3.3 Header placement — success path

The `X-RateLimit-Remaining` header is set **before** `filterChain.doFilter(...)`. This
matters because once `doFilter` is called the servlet container may commit the response
(especially for streaming endpoints). Setting headers before the chain guarantees they
appear in the actual HTTP response.

---

## 4. New concepts introduced

### 4.1 Token-bucket algorithm

A token bucket is a concurrency-control mechanism with these properties:

- The bucket holds up to **capacity** tokens.
- Tokens are added ("refilled") at a fixed rate over time until the bucket is full.
- Each API request consumes one token.
- If the bucket is empty the request is rejected (HTTP 429).

```
             refill rate: 100 tokens / 60 min
             ┌────────────────────────────┐
             │  ●●●●●●●●●●●●●●●●●●●●●●  │  ← capacity 100
             │  ●●●●●●●●●●●●●●●●●●●●●   │
             └────────────────────────────┘
                         ↓ each request consumes 1 token
```

A "greedy" refill (the mode configured in `RateLimitConfig`) adds tokens continuously
rather than in discrete bursts, so a user who waits 30 minutes gets half their tokens
back without having to wait the full hour.

### 4.2 `bucket.getAvailableTokens()` after `tryConsume`

`tryConsume` is atomic: it reads the bucket state from Redis, decrements by 1 if
possible, and writes the new state — all inside a Lua script so no other process can
interleave. After that completes, `getAvailableTokens()` reads the updated count from
Redis so the value reported in the header reflects the post-consume state.

### 4.3 `X-RateLimit-Remaining` header

A de-facto standard response header (not formally standardised but universally
adopted). It tells the client how many requests it may make before the next 429. A
value of `0` accompanies a 429 response to signal that the bucket is exhausted.

### 4.4 `Retry-After` header (RFC 6585 §4)

Defined in RFC 6585. When a server returns HTTP 429 it SHOULD include this header.
The value is an integer number of seconds the client must wait before retrying. HTTP
clients and retry libraries (e.g. Axios retry, Spring's `RestClient` with retry
interceptors) understand this header natively and can honour it automatically.

---

## 5. Files changed

### `src/main/java/com/studylink/blogcms/security/RateLimitFilter.java`

Two targeted edits — no structural changes.

**Edit 1 — success path, add `X-RateLimit-Remaining`**

```java
// Before
if (bucket.tryConsume(1)) {
    log.debug("Rate limit OK — key={}, remaining={}", bucketKeyStr, bucket.getAvailableTokens());
    filterChain.doFilter(request, response);
}

// After
if (bucket.tryConsume(1)) {
    long remaining = bucket.getAvailableTokens();
    response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
    log.debug("Rate limit OK — key={}, remaining={}", bucketKeyStr, remaining);
    filterChain.doFilter(request, response);
}
```

`remaining` is captured in a local variable so the same value goes to both the response
header and the log statement — no second Redis read.

**Edit 2 — 429 path, add `X-RateLimit-Remaining: 0` and `Retry-After: 3600`**

```java
// Before
private void sendTooManyRequestsResponse(HttpServletResponse response, String bucketKey)
        throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    // ... body only
}

// After
private void sendTooManyRequestsResponse(HttpServletResponse response, String bucketKey)
        throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader("X-RateLimit-Remaining", "0");
    response.setHeader("Retry-After", "3600");
    // ... body unchanged
}
```

---

## 6. Testing checklist

| What to test | How to test | Expected result |
|---|---|---|
| Normal request — header present | `curl -i -H "Authorization: Bearer <token>" http://localhost:8082/api/v1/posts` | Response includes `X-RateLimit-Remaining: <n>` where n < capacity |
| Header decrements per request | Send two requests in succession | Second response has a value 1 lower than the first |
| 429 response headers | Send 101 requests rapidly as SUBSCRIBER | 101st response: status 429, `X-RateLimit-Remaining: 0`, `Retry-After: 3600` |
| 429 body | Same as above | Body is `{"status":429,"error":"Too Many Requests","message":"Rate limit exceeded. Try again later."}` |
| Anonymous caller | Request without `Authorization` header | Header present, keyed by IP, SUBSCRIBER capacity (100) |
| ADMIN bypass | Request with ADMIN JWT | `X-RateLimit-Remaining` stays near `Long.MAX_VALUE / 2`, never 429 |
| Actuator skipped | `curl -i http://localhost:8082/actuator/health` | No `X-RateLimit-Remaining` header (filter is skipped) |
