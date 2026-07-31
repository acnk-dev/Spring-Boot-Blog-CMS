# Commit 29 — File Upload REST Endpoint

## Overview

This commit adds `POST /api/v1/posts/{id}/image` to `PostController`. The endpoint accepts a multipart file upload, validates that the file is not empty, and delegates all business logic (ownership check, old-file cleanup, storage, persistence) to `PostService.uploadFeaturedImage`. It returns the full updated `PostResponse` with the new `featuredImageUrl`.

---

## REST API Design

### Why POST and not PUT?

PUT implies idempotency — calling it multiple times with the same payload produces the same result. File uploads are not idempotent: each call writes a new file with a UUID name, deletes the previous one, and stores a different path. POST is the correct verb for a non-idempotent action that creates or replaces a resource.

Additionally, the featured image is a sub-resource of the post (`/posts/{id}/image`). POST on a sub-resource URL is the standard pattern for uploading to a nested resource (compare GitHub's `POST /repos/{owner}/{repo}/releases/{id}/assets`).

### Resource-oriented URL design

```
POST /api/v1/posts/{id}/image
```

- `/api/v1` — API version prefix, enables non-breaking evolution.
- `/posts/{id}` — identifies the parent post resource.
- `/image` — names the specific sub-resource being replaced.

This keeps the URL readable and makes the intent unambiguous without needing a query parameter like `?action=uploadImage`.

### Multipart form-data explained

`multipart/form-data` is the encoding type used when a form contains binary data. The HTTP body is split into named "parts" separated by a boundary string. Each part has its own `Content-Disposition` and `Content-Type` headers. Spring's `MultipartFile` abstraction parses this automatically when you declare `@RequestParam("file") MultipartFile file`.

---

## Controller Responsibilities

The controller is intentionally thin. Its only jobs are:

| Responsibility | Implementation |
|---|---|
| Input validation | Check `file.isEmpty()` — throw `BadRequestException` if true |
| Authentication | Extract `User` from `@AuthenticationPrincipal` |
| Delegation | Call `postService.uploadFeaturedImage(id, file, userId)` |
| Response formatting | Wrap `PostResponse` in `ResponseEntity.ok()` |

Everything else — authorization, old-file deletion, file type validation, storage, persistence — belongs to the service layer. The controller does not inspect file type, file size, or post ownership.

---

## Multipart Request

### Content-Type header

The client must send `Content-Type: multipart/form-data`. If this header is missing or wrong, Spring will return `415 Unsupported Media Type` before the controller method is even called.

### How browsers handle file uploads

When an HTML `<input type="file">` is submitted, the browser automatically:
1. Sets `Content-Type: multipart/form-data; boundary=----WebKitFormBoundary...`
2. Encodes each field as a separate part with a `Content-Disposition` header
3. Streams the binary file data as one of those parts

No manual encoding is required in browser-based clients.

### form-data vs raw binary

| Encoding | Use case | Spring annotation |
|---|---|---|
| `multipart/form-data` | File + metadata fields in one request | `@RequestParam MultipartFile` |
| `application/octet-stream` | Raw binary only, no metadata | `@RequestBody byte[]` |

This endpoint uses `multipart/form-data` because it allows future fields (e.g., `altText`, `caption`) to be added to the same request without changing the transport format.

---

## Security Context

### Extracting the current user

```java
@AuthenticationPrincipal User user
```

Spring Security resolves `@AuthenticationPrincipal` by calling `Authentication.getPrincipal()` on the current `SecurityContext`. Because `UserDetailsServiceImpl` loads a `User` entity and wraps it as the principal, the injected type is the JPA `User` entity directly — no casting needed.

### Authentication object chain

```
SecurityContextHolder
  └─ SecurityContext
       └─ Authentication (UsernamePasswordAuthenticationToken / JwtAuthenticationToken)
            └─ Principal → User entity (set during JWT filter processing)
```

### UserDetails to User mapping

`JwtAuthenticationFilter` validates the JWT, extracts the username/email, loads the `User` via `UserDetailsServiceImpl`, and stores it as the principal. By the time the controller method runs, the `User` entity is fully populated and available via `@AuthenticationPrincipal`.

---

## API Contract

```
POST /api/v1/posts/{id}/image
Authorization: Bearer <jwt-token>
Content-Type: multipart/form-data

Body (form fields):
  file: <binary image data>

Success Response — 200 OK:
{
  "id": 1,
  "title": "My Post Title",
  "slug": "my-post-title",
  "content": "...",
  "excerpt": "...",
  "featuredImageUrl": "/uploads/posts/550e8400-e29b-41d4-a716-446655440000.jpg",
  "status": "DRAFT",
  "author": { ... },
  "category": { ... },
  "tags": [ ... ],
  "viewCount": 0,
  "createdAt": "2026-03-21T10:00:00",
  "updatedAt": "2026-03-21T10:05:00"
}

Error Responses:
  400 Bad Request  — file is empty
  401 Unauthorized — no/invalid JWT token
  403 Forbidden    — user is not owner and not ADMIN
  404 Not Found    — post or user does not exist
  500 Internal     — file write or DB failure
```

---

## Client Examples

### curl

```bash
curl -X POST "http://localhost:8080/api/v1/posts/1/image" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  -F "file=@/path/to/image.jpg"
```

The `-F` flag sets `Content-Type: multipart/form-data` automatically.

### Postman

1. Method: `POST`
2. URL: `http://localhost:8080/api/v1/posts/1/image`
3. Headers: `Authorization: Bearer <token>` (do **not** manually set `Content-Type` — Postman sets the boundary for you)
4. Body tab → select **form-data**
5. Add key `file`, change type dropdown from "Text" to **File**, select your image

### JavaScript fetch with FormData

```javascript
async function uploadFeaturedImage(postId, file, token) {
  const formData = new FormData();
  formData.append("file", file); // file is a File object from <input type="file">

  const response = await fetch(`/api/v1/posts/${postId}/image`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      // Do NOT set Content-Type manually — fetch sets it with the correct boundary
    },
    body: formData,
  });

  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.message);
  }

  return response.json(); // PostResponse
}
```

### React file upload with onChange handler

```jsx
function FeaturedImageUpload({ postId, token }) {
  const handleFileChange = async (event) => {
    const file = event.target.files[0];
    if (!file) return;

    const formData = new FormData();
    formData.append("file", file);

    const res = await fetch(`/api/v1/posts/${postId}/image`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
      body: formData,
    });

    if (!res.ok) throw new Error("Upload failed");
    const post = await res.json();
    console.log("New image URL:", post.featuredImageUrl);
  };

  return <input type="file" accept="image/*" onChange={handleFileChange} />;
}
```

### Angular HttpClient

```typescript
uploadFeaturedImage(postId: number, file: File): Observable<PostResponse> {
  const formData = new FormData();
  formData.append('file', file);

  return this.http.post<PostResponse>(
    `/api/v1/posts/${postId}/image`,
    formData
    // HttpClient sets Content-Type with boundary automatically when body is FormData
  );
}
```

---

## Testing Guide

| Test case | Setup | Expected result |
|---|---|---|
| Valid owner uploads image | Auth as post author, send valid `.jpg` | 200 OK, `featuredImageUrl` updated |
| ADMIN uploads image | Auth as ADMIN (different user), send valid `.png` | 200 OK, ADMIN bypass works |
| Replace existing image | Post already has `featuredImageUrl`, upload new file | Old file deleted, new URL returned |
| Invalid file type | Send `.exe` or `.pdf` | 400 Bad Request (thrown by `FileStorageService`) |
| Empty file | Send file with 0 bytes | 400 Bad Request — "File is empty" |
| No authentication | Omit `Authorization` header | 401 Unauthorized |
| Expired token | Send expired JWT | 401 Unauthorized |
| Wrong user | Auth as user who does not own the post, not ADMIN | 403 Forbidden |
| Post not found | Use a non-existent post ID | 404 Not Found |
| File too large | Send file exceeding `spring.servlet.multipart.max-file-size` | 413 Payload Too Large |

---

## Common Issues

### 413 Request Entity Too Large

Spring Boot defaults: `max-file-size=1MB`, `max-request-size=10MB`. For larger images set in `application.properties`:

```properties
spring.servlet.multipart.max-file-size=10MB
spring.servlet.multipart.max-request-size=10MB
```

The 413 is thrown by the servlet container before reaching the controller, so no custom error handling in the controller is needed.

### 415 Unsupported Media Type

Caused by a missing or incorrect `Content-Type` header on the request. Most clients (curl `-F`, browser FormData, Postman form-data) set this automatically. The issue appears when clients manually set `Content-Type: application/json` or forget to include the multipart boundary.

Fix: never manually set `Content-Type` for multipart requests. Let the HTTP client library set it.

### Missing Content-Type boundary

A subtle variant of the 415 problem. `Content-Type: multipart/form-data` alone is not enough — the header must include the boundary string, e.g.:

```
Content-Type: multipart/form-data; boundary=----WebKitFormBoundary7MA4YWxkTrZu0gW
```

HTTP client libraries handle this automatically. The issue only appears when requests are crafted by hand (e.g., in a raw socket test or a misconfigured script).
