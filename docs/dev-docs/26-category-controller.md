# Commit 26: Create CategoryController

**Date:** March 20, 2026
**Commit Message:** Create CategoryController for category endpoints

---

## Overview

This commit adds `CategoryController` — the HTTP entry point for all category-related operations in the Blog CMS. Categories are the top-level taxonomy for organizing blog posts (e.g., "Spring Boot", "Java", "DevOps").

### What this commit adds
- `GET /api/v1/categories` — list all categories (public)
- `GET /api/v1/categories/{id}` — get category by ID (public)
- `GET /api/v1/categories/slug/{slug}` — get category by slug (public, SEO-friendly)
- `POST /api/v1/categories` — create category (ADMIN only)
- `PUT /api/v1/categories/{id}` — update category (ADMIN only)
- `DELETE /api/v1/categories/{id}` — delete category (ADMIN only)

### Why this commit matters

Without `CategoryController`, the `CategoryService` is unreachable from HTTP. The controller bridges the gap between HTTP requests and business logic. It handles:
- URL routing
- Request deserialization and validation
- Authorization enforcement
- HTTP status codes in responses

---

## Architecture Decisions

### Decision 1: Why a Separate Controller for Categories?

**Short answer:** Single Responsibility Principle.

Each controller owns one resource. `CategoryController` handles `/api/v1/categories`. `PostController` handles `/api/v1/posts`. They never overlap.

**Benefits:**
- Each file is small and focused (~120 lines)
- Easy to find "where does GET /categories live?"
- Teams can work on different controllers in parallel
- Testing is isolated per resource

**Alternative considered:** One `AdminController` with all write operations. Rejected because it would mix concerns (posts + categories + tags) and grow unmanageable.

---

### Decision 2: Why ADMIN-Only for Write Operations?

Categories define the content taxonomy of the entire platform. Allowing authors to create arbitrary categories would:
- Create noise (duplicate or misspelled categories)
- Break navigation and filtering
- Make SEO slug management unpredictable

**Rule of thumb:** Whoever owns the data model owns the taxonomy. Authors create *content* (posts). Admins define *structure* (categories, tags).

---

### Decision 3: Why Allow Public Read Access?

Categories are display data — they appear in navigation menus, filter sidebars, and post metadata. Requiring a login to read them would:
- Break SEO (crawlers don't authenticate)
- Frustrate anonymous readers who want to browse by topic
- Add unnecessary friction before the user even reads a post

**Pattern:** Write operations are protected; read operations are open. This is standard for content-driven sites.

---

## Security Model

### @PreAuthorize("hasRole('ADMIN')")

This annotation is evaluated **before** the method body runs. If the condition is false, Spring throws `AccessDeniedException`, which returns HTTP 403 Forbidden.

```java
@PostMapping
@PreAuthorize("hasRole('ADMIN')")          // ← checked first
public ResponseEntity<CategoryResponse> createCategory(...) {
    // only runs if user has ADMIN role
}
```

**How it works:**
1. Request arrives with `Authorization: Bearer <token>`
2. `JwtAuthenticationFilter` validates the token and sets `SecurityContextHolder`
3. Spring Security reads the `Authentication` object from the context
4. `@PreAuthorize` evaluates `hasRole('ADMIN')` against the authorities in that object
5. Method runs or `AccessDeniedException` is thrown

**Important:** `hasRole('ADMIN')` automatically prepends `ROLE_` prefix, so it matches `ROLE_ADMIN` in the token. This is a Spring Security convention.

---

### Role-Based Access Control (RBAC) Table

| Endpoint                  | SUBSCRIBER | AUTHOR | ADMIN |
|---------------------------|:----------:|:------:|:-----:|
| GET /categories           | ✅         | ✅     | ✅    |
| GET /categories/{id}      | ✅         | ✅     | ✅    |
| GET /categories/slug/{s}  | ✅         | ✅     | ✅    |
| POST /categories          | ❌         | ❌     | ✅    |
| PUT /categories/{id}      | ❌         | ❌     | ✅    |
| DELETE /categories/{id}   | ❌         | ❌     | ✅    |

---

### How JWT Roles Are Checked

```
Client Request
    │
    ▼
JwtAuthenticationFilter
    │  extracts token from "Authorization: Bearer ..."
    │  validates signature + expiry
    │  reads "role" claim from JWT payload (e.g., "ADMIN")
    │  creates UsernamePasswordAuthenticationToken with ROLE_ADMIN authority
    │  sets SecurityContextHolder.getContext().setAuthentication(auth)
    │
    ▼
Spring Security Filter Chain
    │
    ▼
@PreAuthorize("hasRole('ADMIN')")
    │  reads authentication from SecurityContextHolder
    │  checks if ROLE_ADMIN is in granted authorities
    │  throws AccessDeniedException if false
    │
    ▼
Controller Method Executes
```

---

## API Endpoints

### POST /api/v1/categories
**Authorization:** ADMIN only
**Status:** 201 Created

**Request:**
```json
{
  "name": "Spring Boot",
  "description": "Articles about Spring Boot framework"
}
```

**Response:**
```json
{
  "id": 1,
  "name": "Spring Boot",
  "slug": "spring-boot",
  "description": "Articles about Spring Boot framework",
  "createdAt": "2026-03-20T10:15:00",
  "updatedAt": "2026-03-20T10:15:00"
}
```

---

### GET /api/v1/categories
**Authorization:** Public
**Status:** 200 OK

**Response:**
```json
[
  {
    "id": 1,
    "name": "Java",
    "slug": "java",
    "description": "Core Java articles",
    "createdAt": "2026-03-01T09:00:00",
    "updatedAt": "2026-03-01T09:00:00"
  },
  {
    "id": 2,
    "name": "Spring Boot",
    "slug": "spring-boot",
    "description": "Spring Boot framework articles",
    "createdAt": "2026-03-01T09:05:00",
    "updatedAt": "2026-03-01T09:05:00"
  }
]
```

---

### GET /api/v1/categories/{id}
**Authorization:** Public
**Status:** 200 OK | 404 Not Found

**Response (200):**
```json
{
  "id": 1,
  "name": "Java",
  "slug": "java",
  "description": "Core Java articles",
  "createdAt": "2026-03-01T09:00:00",
  "updatedAt": "2026-03-01T09:00:00"
}
```

**Response (404):**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Category not found with id: 99",
  "timestamp": "2026-03-20T11:00:00"
}
```

---

### GET /api/v1/categories/slug/{slug}
**Authorization:** Public
**Status:** 200 OK | 404 Not Found

**Example:** `GET /api/v1/categories/slug/spring-boot`

**Response (200):** Same structure as GET by ID.

---

### PUT /api/v1/categories/{id}
**Authorization:** ADMIN only
**Status:** 200 OK | 404 Not Found

**Request:**
```json
{
  "name": "Spring Boot 3",
  "description": "Updated description for Spring Boot 3 articles"
}
```

**Response:** Updated `CategoryResponse`. Slug is regenerated from the new name (`spring-boot-3`).

---

### DELETE /api/v1/categories/{id}
**Authorization:** ADMIN only
**Status:** 204 No Content | 404 Not Found | 400 Bad Request

**Notes:**
- Returns 204 with empty body on success
- Returns 400 if posts are still assigned to this category (cannot orphan posts)

---

## HTTP Status Codes Explained

| Code | Meaning | When used |
|------|---------|-----------|
| 200 | OK | Successful GET or PUT |
| 201 | Created | Successful POST (new resource created) |
| 204 | No Content | Successful DELETE (nothing to return) |
| 400 | Bad Request | Validation failure or business rule violation |
| 401 | Unauthorized | No token or invalid token |
| 403 | Forbidden | Valid token but insufficient role |
| 404 | Not Found | Category ID or slug doesn't exist |

**Why 201 for POST and not 200?**
RFC 7231 defines 201 specifically for "the request has been fulfilled and has resulted in one or more new resources being created." Using 201 makes the API semantically correct and helps clients know a new resource was created.

**Why 204 for DELETE and not 200?**
204 means "success with no body." Since a deleted resource no longer exists, there's nothing meaningful to return. 204 is the standard for successful deletions.

---

## Code Flow Diagram

```
HTTP Request
    │
    ▼
┌─────────────────────────┐
│  JwtAuthenticationFilter │  ← validates Bearer token
│  Sets SecurityContext    │  ← stores user + roles
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│   CategoryController    │  ← routes URL to method
│   @PreAuthorize check   │  ← enforces ADMIN role
│   @Valid validation     │  ← validates request body
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│    CategoryService      │  ← business logic
│    (CategoryServiceImpl)│  ← slug generation, duplicate check
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│   CategoryRepository    │  ← Spring Data JPA
│   (JPA/Hibernate)       │  ← query generation
└─────────────────────────┘
    │
    ▼
┌─────────────────────────┐
│      PostgreSQL         │  ← actual data storage
│   categories table      │
└─────────────────────────┘
    │
    ▼  (reverse path: entity → DTO → JSON)
HTTP Response
```

---

## Key Concepts

### REST API Best Practices Applied

**1. Nouns in URLs, not verbs**
```
✅ POST   /api/v1/categories       (create)
✅ GET    /api/v1/categories       (list)
✅ PUT    /api/v1/categories/{id}  (update)
✅ DELETE /api/v1/categories/{id}  (delete)

❌ POST /api/v1/createCategory
❌ GET  /api/v1/getCategory
```
The HTTP method IS the verb. The URL is the resource.

**2. Consistent response types**
- Single resource: `ResponseEntity<CategoryResponse>`
- Collection: `ResponseEntity<List<CategoryResponse>>`
- No content: `ResponseEntity<Void>`

**3. Proper status codes** (see table above)

**4. @Valid for input validation**
```java
public ResponseEntity<CategoryResponse> createCategory(
        @Valid @RequestBody CreateCategoryRequest request) {
```
If `@NotBlank` or `@Size` constraints in `CreateCategoryRequest` are violated, Spring returns 400 automatically with field-level error messages. The controller never sees invalid data.

---

### Controller Responsibilities (What Controllers Should and Shouldn't Do)

**Controllers SHOULD:**
- Map HTTP methods and URLs to Java methods
- Extract path variables, query params, request bodies
- Validate input with `@Valid`
- Enforce authorization with `@PreAuthorize`
- Return appropriate HTTP status codes
- Log incoming requests for observability

**Controllers SHOULD NOT:**
- Contain business logic (that's the service's job)
- Talk directly to repositories (bypasses service layer)
- Transform entities (use mappers or DTOs)
- Catch generic exceptions (use `@ControllerAdvice`)

**The "thin controller" principle:** Controllers are traffic cops. They direct traffic. They don't build the roads.

---

### Slug-Based Retrieval (SEO-Friendly URLs)

A **slug** is a URL-safe string derived from a resource's name:
- `"Spring Boot"` → `"spring-boot"`
- `"Java 21 & Beyond!"` → `"java-21-beyond"`

**Why slugs matter:**
1. **SEO:** `/categories/spring-boot` ranks better than `/categories/3`
2. **Readability:** Users can understand the URL without clicking
3. **Stability:** IDs change across environments; slugs are content-stable
4. **Bookmarks:** Slugs remain valid even if the database is restored

**Slug endpoint example:**
```
Frontend URL: /blog/category/spring-boot
    │
    ▼
API call: GET /api/v1/categories/slug/spring-boot
    │
    ▼
CategoryService.getCategoryBySlug("spring-boot")
    │
    ▼
CategoryRepository.findBySlug("spring-boot")
```

The slug is generated once at creation time by `SlugUtil` in `CategoryServiceImpl`. If the name is updated, the slug is regenerated.

---

## Testing Guide

### Prerequisites
1. Application running on `http://localhost:8081`
2. Valid ADMIN JWT token (get one via `POST /api/v1/auth/login`)

### Step 1: Get ADMIN Token

```bash
curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@example.com",
    "password": "your-password"
  }' | jq '.accessToken'
```

Save the token:
```bash
TOKEN="eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

### Step 2: Create a Category (ADMIN)

```bash
curl -X POST http://localhost:8081/api/v1/categories \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Spring Boot",
    "description": "Articles about the Spring Boot framework"
  }'
```

**Expected:** 201 Created with category JSON including generated slug `spring-boot`.

---

### Step 3: Get All Categories (Public)

```bash
curl http://localhost:8081/api/v1/categories
```

**Expected:** 200 OK with JSON array of categories. No token needed.

---

### Step 4: Get Category by ID (Public)

```bash
curl http://localhost:8081/api/v1/categories/1
```

**Expected:** 200 OK with single category JSON.

---

### Step 5: Get Category by Slug (Public)

```bash
curl http://localhost:8081/api/v1/categories/slug/spring-boot
```

**Expected:** 200 OK with the "Spring Boot" category.

---

### Step 6: Update a Category (ADMIN)

```bash
curl -X PUT http://localhost:8081/api/v1/categories/1 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Spring Boot 3",
    "description": "Articles about Spring Boot 3 and beyond"
  }'
```

**Expected:** 200 OK with updated name and new slug `spring-boot-3`.

---

### Step 7: Delete a Category (ADMIN)

```bash
curl -X DELETE http://localhost:8081/api/v1/categories/1 \
  -H "Authorization: Bearer $TOKEN"
```

**Expected:** 204 No Content (empty body).

---

### Postman Collection Setup

1. Create collection: `Blog CMS`
2. Add environment variable: `baseUrl = http://localhost:8081`, `token = <your token>`
3. Set Authorization tab to `Bearer Token`, value `{{token}}`
4. Create request for each endpoint above

---

## Common Issues

### 403 Forbidden — User Doesn't Have ADMIN Role

**Symptom:**
```json
{
  "status": 403,
  "error": "Forbidden",
  "message": "Access Denied"
}
```

**Causes:**
1. Token belongs to a SUBSCRIBER or AUTHOR account
2. Token is missing entirely
3. Token is expired and was not refreshed

**Fix:**
- Log in with an ADMIN account
- Check the role claim in your JWT at [jwt.io](https://jwt.io)
- Refresh your access token if expired

---

### 404 Not Found — Category Doesn't Exist

**Symptom:**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Category not found with id: 99"
}
```

**Causes:**
1. Wrong ID in the URL path
2. Category was deleted
3. Testing against wrong environment (dev vs staging)

**Fix:**
- First call `GET /api/v1/categories` to list valid IDs
- Verify you're hitting the correct environment

---

### 400 Bad Request — Validation Failed

**Symptom:**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": {
    "name": "Name is required",
    "description": "Description cannot exceed 500 characters"
  }
}
```

**Causes:**
1. `name` is blank or missing
2. `name` is shorter than 2 or longer than 100 characters
3. `description` exceeds 500 characters
4. Request body is malformed JSON

**Fix:**
- Ensure `name` is between 2–100 characters
- Keep `description` under 500 characters
- Validate JSON syntax before sending

---

### 400 Bad Request — Delete Blocked by Posts

**Symptom:**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Cannot delete category: 3 posts are assigned to it"
}
```

**Fix:**
- Reassign all posts to a different category first
- Then retry the delete

---

## Commit Stats

| Metric | Value |
|--------|-------|
| New files | 1 |
| File | `CategoryController.java` |
| Lines of code | ~130 |
| Endpoints added | 6 |
| Public endpoints | 3 |
| Admin-only endpoints | 3 |

---

## What Works Now

- Full CRUD for categories via HTTP
- Public browsing by ID and slug
- ADMIN-only write operations
- Automatic slug generation on create/update
- Input validation with descriptive error messages
- Structured logging for all operations

## What's Missing (Next Steps)

- `TagController` — same pattern for tags resource
- Search endpoint: `GET /categories?search=spring` using `CategoryService.searchCategories()`
- Pagination for categories (currently returns full list, acceptable while category count is small)
- Integration tests for each endpoint

---

## Resources

- [Spring MVC @RestController docs](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/bind/annotation/RestController.html)
- [Spring Security @PreAuthorize](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- [HTTP Status Codes — MDN](https://developer.mozilla.org/en-US/docs/Web/HTTP/Status)
- [REST API Design Best Practices — Baeldung](https://www.baeldung.com/rest-with-spring-series)
- [URL slug best practices — Google SEO](https://developers.google.com/search/docs/crawling-indexing/url-structure)
