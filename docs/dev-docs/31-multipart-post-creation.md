# Commit 30 — Single-Request Post Creation with Optional Image

## Overview

Before this commit, creating a post with a featured image required two separate HTTP requests:
1. `POST /api/v1/posts` — create the post (JSON body)
2. `POST /api/v1/posts/{id}/image` — upload the image (multipart)

This commit collapses both into a single `POST /api/v1/posts` call that accepts `multipart/form-data` with a JSON part (`post`) and an optional image part (`file`). The dedicated image endpoint (`POST /api/v1/posts/{id}/image`) still exists for adding or replacing images on existing posts.

---

## Problem Statement

### Two API calls is bad UX

Every extra round-trip the frontend has to make is a potential failure point. If the first call succeeds and the second fails, the UI must decide whether to retry, roll back, or silently continue — all of which add complexity. A single call makes the happy path trivial and the error path simpler.

### Network overhead

Each HTTP request incurs TCP handshake latency, TLS overhead, header processing, and server-side routing. Two requests double this overhead. On a mobile connection or a slow network, the difference is perceptible.

### Partial state (post without image)

Between the two calls, the database contains a post with no `featuredImageUrl`. If the second call never arrives (browser closed, network drop, frontend bug), that post remains in a permanently image-less state even though the user intended to have an image. A single atomic call from the client's perspective eliminates this window.

### Transaction concerns

Two separate requests are two separate transactions. Even if both succeed, there is a brief moment where the post exists without its image. A single request allows the image URL to be set before returning the response, giving the client a fully consistent view of the created resource.

---

## Solution: Multipart Form Data

### What is multipart/form-data?

`multipart/form-data` is an HTTP encoding type that splits the request body into named sections ("parts"), each with its own `Content-Type`. This allows binary data (files) and structured data (JSON) to travel in the same request without encoding tricks.

### Why use it for file + JSON?

`application/json` can only carry text. `application/octet-stream` can only carry one binary blob. `multipart/form-data` is the only standard encoding that can carry both a JSON object and a binary file together in one request body.

### Content-Type header structure

```
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW
```

The `boundary` string separates each part. The client (browser or HTTP library) generates this string and includes it in the header. **Never set `Content-Type` manually** in client code when using `FormData` — the library sets the boundary automatically.

---

## @RequestPart vs @RequestParam

| Annotation | Best for | Deserialization |
|---|---|---|
| `@RequestPart` | Complex objects (JSON) and files | Spring uses `HttpMessageConverter` (Jackson) for JSON parts; `MultipartFile` for binary parts |
| `@RequestParam` | Simple scalar values (strings, numbers, booleans) | Spring binds from the raw form-data string |

Use `@RequestPart("post")` for the `CreatePostRequest` because Spring will parse the part's `Content-Type: application/json` header and deserialize it via Jackson — exactly as if it were a `@RequestBody`. Use `@RequestPart("file")` for the `MultipartFile` because it is binary data, not a scalar.

If you used `@RequestParam` for the JSON part, Spring would try to bind the raw JSON string to a `CreatePostRequest` POJO directly, which fails.

---

## Request Structure

The wire format of a request with both parts:

```
POST /api/v1/posts HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary

------WebKitFormBoundary
Content-Disposition: form-data; name="post"
Content-Type: application/json

{
  "title": "My Post",
  "content": "This is the post content.",
  "categoryId": 1,
  "tagIds": [1, 2],
  "status": "DRAFT"
}
------WebKitFormBoundary
Content-Disposition: form-data; name="file"; filename="hero.jpg"
Content-Type: image/jpeg

[binary JPEG data]
------WebKitFormBoundary--
```

Request without an image (file part omitted entirely):

```
POST /api/v1/posts HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary

------WebKitFormBoundary
Content-Disposition: form-data; name="post"
Content-Type: application/json

{
  "title": "Draft Without Image",
  "content": "Content here.",
  "categoryId": 2,
  "status": "DRAFT"
}
------WebKitFormBoundary--
```

---

## Code Flow

```
POST /api/v1/posts (multipart/form-data)
│
├─→ Spring Security: verify JWT, check AUTHOR or ADMIN role
│
├─→ Extract "post" part → deserialize JSON → CreatePostRequest
│
├─→ @Valid → validate CreatePostRequest fields
│   └─ Validation fails → 400 Bad Request (no file processing)
│
├─→ postService.createPost(request, userId)
│   └─ Returns PostResponse with new post ID
│
├─→ File provided and not empty?
│   ├─ Yes → postService.uploadFeaturedImage(postId, file, userId)
│   │         └─ Returns updated PostResponse with featuredImageUrl
│   └─ No  → use original PostResponse (featuredImageUrl = null)
│
└─→ ResponseEntity.status(201).body(response)
```

---

## Why the File is Optional

- Authors may create a draft before the image is ready.
- Some posts (link roundups, text-only essays) never need a featured image.
- The dedicated `POST /api/v1/posts/{id}/image` endpoint still exists for adding or replacing images after creation.
- Making the file optional avoids forcing the frontend to implement a two-step wizard for a common use case.

---

## Transaction Behaviour

Post creation and file upload are separate operations:

| Step | Transaction | On failure |
|---|---|---|
| `postService.createPost()` | Own `@Transactional` — committed before returning | Post not created, no file attempted |
| `postService.uploadFeaturedImage()` | Own `@Transactional` | Post exists with `featuredImageUrl = null` |

**Post created but file upload fails:** The post is persisted with no image. This is acceptable because the image can be retried via `POST /api/v1/posts/{id}/image` without needing to recreate the post.

**Alternative — full rollback:** You could wrap both calls in a single service method annotated `@Transactional` and delete the stored file in a `catch` block if the DB save fails. The trade-off is complexity: file I/O is not transactional, so compensating for a failed DB save requires manual cleanup code and adds coupling between the post service and the file service at the transaction level. For a CMS use case, tolerating an image-less post in exchange for simpler code is the right call.

---

## Frontend Examples

### JavaScript fetch with FormData

```javascript
async function createPost(postData, imageFile, token) {
  const formData = new FormData();

  // JSON part — Blob forces the correct Content-Type on the part
  formData.append('post', new Blob([JSON.stringify(postData)], {
    type: 'application/json'
  }));

  // File part — omit entirely if no image
  if (imageFile) {
    formData.append('file', imageFile);
  }

  // Do NOT set Content-Type manually — fetch sets boundary automatically
  const response = await fetch('/api/v1/posts', {
    method: 'POST',
    headers: { 'Authorization': `Bearer ${token}` },
    body: formData
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message);
  }
  return response.json(); // PostResponse
}
```

### React

```jsx
function CreatePostForm({ token }) {
  const handleSubmit = async (postData, imageFile) => {
    const formData = new FormData();
    formData.append('post', new Blob([JSON.stringify(postData)], {
      type: 'application/json'
    }));
    if (imageFile) {
      formData.append('file', imageFile);
    }

    const res = await fetch('/api/v1/posts', {
      method: 'POST',
      headers: { 'Authorization': `Bearer ${token}` },
      body: formData
    });
    const created = await res.json();
    console.log('Created:', created.id, created.featuredImageUrl);
  };
}
```

### Angular HttpClient

```typescript
createPost(postData: CreatePostRequest, imageFile?: File): Observable<PostResponse> {
  const formData = new FormData();
  formData.append('post', new Blob([JSON.stringify(postData)], {
    type: 'application/json'
  }));
  if (imageFile) {
    formData.append('file', imageFile);
  }

  // HttpClient infers Content-Type + boundary automatically from FormData
  return this.http.post<PostResponse>('/api/v1/posts', formData);
}
```

### curl — with image

```bash
# Using a JSON file
echo '{"title":"My Post","content":"Hello world","categoryId":1}' > post.json

curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "post=@post.json;type=application/json" \
  -F "file=@hero.jpg"

# Inline JSON
curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F 'post={"title":"My Post","content":"Hello world","categoryId":1};type=application/json' \
  -F "file=@hero.jpg"
```

### curl — without image

```bash
curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F 'post={"title":"Draft","content":"No image yet","categoryId":1};type=application/json'
```

### Postman

1. Method: `POST`, URL: `http://localhost:8080/api/v1/posts`
2. Headers: `Authorization: Bearer <token>` — **do not** add `Content-Type`
3. Body tab → **form-data**
4. Row 1: Key = `post`, Type = **Text**, Value = `{"title":"...","content":"...","categoryId":1}` — then hover the key, click the pencil icon, set Content-Type = `application/json`
5. Row 2 (optional): Key = `file`, Type = **File**, select image

---

## Testing Guide

### Test 1 — Create post with image

```bash
echo '{"title":"Test Post","content":"Content here","categoryId":1}' > post.json

curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "post=@post.json;type=application/json" \
  -F "file=@test-image.jpg"
```

Expected: `201 Created`, `featuredImageUrl` is a non-null path.

### Test 2 — Create post without image

```bash
curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F 'post={"title":"Draft","content":"No image","categoryId":1};type=application/json'
```

Expected: `201 Created`, `featuredImageUrl` is `null`.

### Test 3 — Invalid file type

```bash
curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "post=@post.json;type=application/json" \
  -F "file=@document.pdf"
```

Expected: `400 Bad Request` — rejected by `FileStorageService`.

### Test 4 — File exceeds size limit

```bash
dd if=/dev/zero of=large.jpg bs=1M count=15   # 15 MB dummy file

curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F "post=@post.json;type=application/json" \
  -F "file=@large.jpg"
```

Expected: `413 Payload Too Large` (enforced by Spring's multipart config before the controller runs).

### Test 5 — Invalid JSON in "post" part

```bash
curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F 'post={not valid json};type=application/json'
```

Expected: `400 Bad Request` — Jackson deserialization failure.

### Test 6 — Missing required fields

```bash
curl -X POST http://localhost:8080/api/v1/posts \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -F 'post={"title":"Only Title"};type=application/json'
```

Expected: `400 Bad Request` — `@Valid` constraint violations.

---

## Common Issues & Solutions

### 415 Unsupported Media Type

**Cause:** `@PostMapping` is missing `consumes = MediaType.MULTIPART_FORM_DATA_VALUE`.
**Fix:** Already set — `@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)`.

### 400 — "Required request part 'post' is not present"

**Cause:** The client named the JSON part something other than `"post"` (e.g., `"data"`, `"body"`).
**Fix:** The part name in `FormData.append(...)` must exactly match the `@RequestPart("post")` annotation value.

### 400 — JSON deserialization failure

**Cause:** The `"post"` part was sent without `Content-Type: application/json`. Spring cannot determine how to deserialize the raw string.
**Fix:** Always wrap the JSON string in a `Blob` with the correct type in JavaScript:
```javascript
new Blob([JSON.stringify(data)], { type: 'application/json' })
```

### File part not received by backend

**Cause:** Part name mismatch — e.g., frontend sends `"image"` but backend expects `"file"`.
**Fix:** `@RequestPart("file")` must match the `FormData.append('file', ...)` key exactly.

### File appears empty on the server

**Cause:** The `File` object was appended to `FormData` before the user actually selected a file, capturing a 0-byte placeholder.
**Fix:** Validate on the frontend before appending:
```javascript
if (imageFile && imageFile.size > 0) {
  formData.append('file', imageFile);
}
```

---

## Benefits

- Single HTTP request — better UX, one round-trip saved
- No partial state window — post and image arrive together
- Reduced server load — one authentication check, one routing decision
- Cleaner frontend code — no chained promises or sequential calls
- Better perceived performance on slow networks

## Trade-offs

- More complex request format — `FormData` requires more care than plain JSON
- Harder to test in Swagger UI — multipart + JSON part is awkward to configure
- Postman requires explicit `Content-Type` override on the JSON part
- File upload failure leaves post without image (acceptable but must be communicated to users)

---

## Available Endpoints After This Commit

| Endpoint | When to use |
|---|---|
| `POST /api/v1/posts` (multipart) | Creating a new post, image available immediately |
| `POST /api/v1/posts/{id}/image` | Adding/replacing image on an existing post |

---

## Security Considerations

- JWT authentication required (`Authorization: Bearer` header)
- Role check (`AUTHOR` or `ADMIN`) enforced by `@PreAuthorize` before any processing
- File type validation (MIME + extension) handled by `FileStorageService`
- File size limit enforced by `spring.servlet.multipart.max-file-size`
- Path traversal prevention is in `FileStorageService` (UUID-based filenames)

---

## Future Enhancements

- Progress events for large file uploads (SSE or WebSocket)
- Server-side image resizing/compression before storage
- Presigned S3 URLs for direct browser-to-S3 upload (bypasses server bandwidth)
- Multiple images per post (gallery support)
- Client-side crop/preview before upload