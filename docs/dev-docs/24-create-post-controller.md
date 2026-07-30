# Commit 24: Create PostController (Will Demonstrate N+1)

**Date:** February 12, 2026
**Commit Message:** Create PostController for post endpoints

---

## Development Mindset & Decision Process

### Why PostController Now?

**Current State:**
- PostService with full CRUD operations
- CategoryService and TagService ready
- DTOs and mappers implemented
- But no REST endpoints to access any of this!

**Goal:**
- Create REST API endpoints for posts
- Implement role-based access control
- Support pagination for list endpoints
- **Demonstrate N+1 query problem** (intentionally)

**Teaching Point:** The controller is the entry point to your API. It handles HTTP concerns (request/response, status codes, validation) and delegates business logic to services. Keep controllers thin!

---

## Decision 1: REST API Design

### Endpoint Structure

```
/api/v1/posts
├── POST   /                    → Create post
├── GET    /                    → List all posts (paginated)
├── GET    /{id}                → Get post by ID
├── GET    /slug/{slug}         → Get post by slug
├── PUT    /{id}                → Update post
├── DELETE /{id}                → Delete post
├── GET    /user/{userId}       → Get user's posts
├── GET    /search?query=       → Search posts
├── GET    /category/{id}       → Get posts by category
├── GET    /tag/{id}            → Get posts by tag
└── POST   /{id}/publish        → Publish draft
```

### Why `/api/v1/`?

**API versioning strategies:**

| Strategy | Example | Pros | Cons |
|----------|---------|------|------|
| **URL Path** (our choice) | `/api/v1/posts` | Clear, easy to route | URL pollution |
| Header | `Accept: application/vnd.api.v1+json` | Clean URLs | Hard to test |
| Query Param | `/api/posts?version=1` | Flexible | Easy to forget |

**We use URL path because:**
- Most common in industry
- Easy to understand
- Easy to test with Postman/curl
- Clear separation of versions

---

## Decision 2: Role-Based Access Control

### Access Matrix

| Endpoint | SUBSCRIBER | AUTHOR | ADMIN |
|----------|------------|--------|-------|
| GET /posts | ✓ | ✓ | ✓ |
| GET /posts/{id} | ✓ | ✓ | ✓ |
| POST /posts | ✗ | ✓ | ✓ |
| PUT /posts/{id} | ✗ | Own only | ✓ |
| DELETE /posts/{id} | ✗ | Own only | ✓ |
| POST /posts/{id}/publish | ✗ | Own only | ✓ |

### Implementation with @PreAuthorize

```java
@PostMapping
@PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
public ResponseEntity<PostResponse> createPost(
        @Valid @RequestBody CreatePostRequest request,
        @AuthenticationPrincipal User user) {
    // ...
}
```

### How @PreAuthorize Works

```java
// Check single role
@PreAuthorize("hasRole('ADMIN')")

// Check multiple roles
@PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")

// Check permission
@PreAuthorize("hasAuthority('POST_CREATE')")

// Custom expression
@PreAuthorize("#userId == authentication.principal.id")
```

### Enabling Method Security

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)  // Required for @PreAuthorize
public class SecurityConfig {
    // ...
}
```

---

## Decision 3: Getting the Authenticated User

### @AuthenticationPrincipal

```java
@PostMapping
public ResponseEntity<PostResponse> createPost(
        @RequestBody CreatePostRequest request,
        @AuthenticationPrincipal User user) {  // Spring injects current user

    postService.createPost(request, user.getId());
}
```

**How it works:**
1. JWT filter extracts token from header
2. Filter loads User from database
3. User set in SecurityContext
4. @AuthenticationPrincipal injects it

### Alternative: SecurityContextHolder

```java
@PostMapping
public ResponseEntity<PostResponse> createPost(@RequestBody CreatePostRequest request) {
    User user = (User) SecurityContextHolder.getContext()
            .getAuthentication()
            .getPrincipal();

    postService.createPost(request, user.getId());
}
```

**@AuthenticationPrincipal is better because:**
- Cleaner code
- Easier to test (just pass user in test)
- Type-safe

---

## Decision 4: Pagination Parameters

### Standard Pagination

```
GET /api/v1/posts?page=0&size=10&sortBy=publishedAt&sortDir=desc
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `page` | 0 | Page number (0-indexed) |
| `size` | 10 | Items per page |
| `sortBy` | publishedAt | Field to sort by |
| `sortDir` | desc | Sort direction (asc/desc) |

### Implementation

```java
@GetMapping
public ResponseEntity<Page<PostResponse>> getAllPosts(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size,
        @RequestParam(defaultValue = "publishedAt") String sortBy,
        @RequestParam(defaultValue = "desc") String sortDir) {

    Page<PostResponse> posts = postService.getAllPosts(page, size, sortBy, sortDir);
    return ResponseEntity.ok(posts);
}
```

### Page Response Structure

```json
{
  "content": [
    { "id": 1, "title": "Post 1", ... },
    { "id": 2, "title": "Post 2", ... }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "sort": {
      "sorted": true,
      "direction": "DESC"
    }
  },
  "totalElements": 25,
  "totalPages": 3,
  "last": false,
  "first": true,
  "numberOfElements": 10
}
```

---

## Decision 5: Demonstrating N+1 Query Problem

### What Happens When You Call GET /posts

```
Request: GET /api/v1/posts?page=0&size=10

Hibernate executes:
1. SELECT * FROM posts WHERE status='PUBLISHED' AND is_deleted=false LIMIT 10
2. SELECT * FROM users WHERE id = 1      (author of post 1)
3. SELECT * FROM users WHERE id = 2      (author of post 2)
4. SELECT * FROM users WHERE id = 3      (author of post 3)
... (7 more user queries)
11. SELECT * FROM categories WHERE id = 1  (category of post 1)
12. SELECT * FROM categories WHERE id = 2  (category of post 2)
... (8 more category queries)
21. SELECT * FROM post_tags WHERE post_id = 1  (tags of post 1)
22. SELECT * FROM post_tags WHERE post_id = 2  (tags of post 2)
... (8 more tag queries)

Total: 31 queries for 10 posts!
```

### Enabling Query Logging

**application.properties:**
```properties
# SQL Logging
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true

# Hibernate Statistics
spring.jpa.properties.hibernate.generate_statistics=true
logging.level.org.hibernate.stat=DEBUG
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

### What You'll See in Console

```
Hibernate:
    select
        p1_0.id,
        p1_0.category_id,
        p1_0.content,
        ...
    from
        posts p1_0
    where
        p1_0.status=?
        and p1_0.is_deleted=?
    limit ?

Hibernate:
    select
        u1_0.id,
        u1_0.avatar_url,
        ...
    from
        users u1_0
    where
        u1_0.id=?

Hibernate:
    select
        u1_0.id,
        ...
    from
        users u1_0
    where
        u1_0.id=?

... (many more queries)

Session Metrics {
    31 JDBC statements
    1234 nanoseconds spent executing 31 JDBC statements
}
```

### Log Warning in Controller

```java
@GetMapping
public ResponseEntity<Page<PostResponse>> getAllPosts(...) {
    log.warn(">>> N+1 QUERY PROBLEM: Watch the SQL logs below! <<<");
    // ...
}
```

---

## Files Created/Modified

### 1. PostController.java (Created)

```java
@RestController
@RequestMapping("/api/v1/posts")
@RequiredArgsConstructor
@Slf4j
public class PostController {

    private final PostService postService;

    // Create post - AUTHOR, ADMIN only
    @PostMapping
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    public ResponseEntity<PostResponse> createPost(
            @Valid @RequestBody CreatePostRequest request,
            @AuthenticationPrincipal User user) {
        PostResponse response = postService.createPost(request, user.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // Get all posts - Public (N+1 problem!)
    @GetMapping
    public ResponseEntity<Page<PostResponse>> getAllPosts(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "publishedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        Page<PostResponse> posts = postService.getAllPosts(page, size, sortBy, sortDir);
        return ResponseEntity.ok(posts);
    }

    // Get post by ID - Public
    @GetMapping("/{id}")
    public ResponseEntity<PostResponse> getPostById(@PathVariable Long id) {
        PostResponse post = postService.getPostById(id);
        postService.incrementViewCount(id);
        return ResponseEntity.ok(post);
    }

    // Get post by slug - Public
    @GetMapping("/slug/{slug}")
    public ResponseEntity<PostResponse> getPostBySlug(@PathVariable String slug) {
        PostResponse post = postService.getPostBySlug(slug);
        return ResponseEntity.ok(post);
    }

    // Update post - AUTHOR (own), ADMIN
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    public ResponseEntity<PostResponse> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request,
            @AuthenticationPrincipal User user) {
        PostResponse response = postService.updatePost(id, request, user.getId());
        return ResponseEntity.ok(response);
    }

    // Delete post - AUTHOR (own), ADMIN
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        postService.deletePost(id, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ... other endpoints
}
```

### 2. SecurityConfig.java (Modified)

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)  // Added
@RequiredArgsConstructor
public class SecurityConfig {
    // ...
}
```

### 3. application.properties (Modified)

```properties
# Added for N+1 demonstration
spring.jpa.properties.hibernate.generate_statistics=true
logging.level.org.hibernate.stat=DEBUG
logging.level.org.hibernate.SQL=DEBUG
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
```

---

## Testing with Postman

### Prerequisites

1. Start the application
2. Have a user with AUTHOR or ADMIN role
3. Have at least one category in database
4. (Optional) Create some tags

### 1. Register/Login to Get JWT Token

```http
POST http://localhost:8081/api/v1/auth/register
Content-Type: application/json

{
  "email": "author@test.com",
  "password": "password123",
  "name": "Test Author"
}
```

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiIs...",
  "refreshToken": "eyJhbGciOiJIUzI1NiIs...",
  "user": {
    "id": 1,
    "email": "author@test.com",
    "name": "Test Author",
    "role": "SUBSCRIBER"
  }
}
```

> **Note:** New users are SUBSCRIBER by default. You may need to manually update the role to AUTHOR in the database:
> ```sql
> UPDATE users SET role = 'AUTHOR' WHERE email = 'author@test.com';
> ```

### 2. Create a Category (Direct SQL for now)

```sql
INSERT INTO categories (name, slug, description, created_at, updated_at)
VALUES ('Technology', 'technology', 'Tech posts', NOW(), NOW());
```

### 3. Create a Post

```http
POST http://localhost:8081/api/v1/posts
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "title": "Getting Started with Spring Boot",
  "content": "Spring Boot makes it easy to create stand-alone, production-grade Spring based Applications. In this tutorial, we will learn how to build a REST API using Spring Boot, JPA, and PostgreSQL database.",
  "excerpt": "Learn to build REST APIs with Spring Boot",
  "categoryId": 1,
  "tagIds": [],
  "status": "PUBLISHED"
}
```

**Expected Response (201 Created):**
```json
{
  "id": 1,
  "title": "Getting Started with Spring Boot",
  "slug": "getting-started-with-spring-boot",
  "content": "Spring Boot makes it easy...",
  "excerpt": "Learn to build REST APIs with Spring Boot",
  "featuredImageUrl": null,
  "status": "PUBLISHED",
  "viewCount": 0,
  "likeCount": 0,
  "author": {
    "id": 1,
    "name": "Test Author",
    "avatarUrl": null
  },
  "category": {
    "id": 1,
    "name": "Technology",
    "slug": "technology",
    "description": "Tech posts"
  },
  "tags": [],
  "publishedAt": "2024-01-15T10:30:00",
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

### 4. Get All Posts (Observe N+1!)

```http
GET http://localhost:8081/api/v1/posts?page=0&size=10
```

**Watch the console!** You'll see multiple SQL queries.

**Expected Response:**
```json
{
  "content": [
    {
      "id": 1,
      "title": "Getting Started with Spring Boot",
      "slug": "getting-started-with-spring-boot",
      ...
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10
  },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "first": true
}
```

### 5. Get Post by ID

```http
GET http://localhost:8081/api/v1/posts/1
```

### 6. Get Post by Slug

```http
GET http://localhost:8081/api/v1/posts/slug/getting-started-with-spring-boot
```

### 7. Update Post

```http
PUT http://localhost:8081/api/v1/posts/1
Authorization: Bearer {{accessToken}}
Content-Type: application/json

{
  "title": "Updated: Getting Started with Spring Boot",
  "content": "Updated content here..."
}
```

### 8. Search Posts

```http
GET http://localhost:8081/api/v1/posts/search?query=spring&page=0&size=10
```

### 9. Get Posts by Category

```http
GET http://localhost:8081/api/v1/posts/category/1?page=0&size=10
```

### 10. Publish a Draft

```http
POST http://localhost:8081/api/v1/posts/1/publish
Authorization: Bearer {{accessToken}}
```

### 11. Delete Post

```http
DELETE http://localhost:8081/api/v1/posts/1
Authorization: Bearer {{accessToken}}
```

**Expected Response:** `204 No Content`

---

## Postman Collection Structure

```
Blog CMS API
├── Auth
│   ├── Register
│   ├── Login
│   └── Refresh Token
├── Posts
│   ├── Create Post
│   ├── Get All Posts
│   ├── Get Post by ID
│   ├── Get Post by Slug
│   ├── Update Post
│   ├── Delete Post
│   ├── Get Posts by User
│   ├── Search Posts
│   ├── Get Posts by Category
│   ├── Get Posts by Tag
│   └── Publish Post
├── Categories (coming soon)
└── Tags (coming soon)
```

### Environment Variables

```json
{
  "baseUrl": "http://localhost:8081",
  "accessToken": "",
  "refreshToken": "",
  "userId": ""
}
```

---

## Error Responses

### 400 Bad Request (Validation Error)

```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "errors": [
    {
      "field": "title",
      "message": "Title must be between 5 and 200 characters"
    },
    {
      "field": "content",
      "message": "Content must be at least 50 characters"
    }
  ]
}
```

### 401 Unauthorized

```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 401,
  "error": "Unauthorized",
  "message": "Full authentication is required"
}
```

### 403 Forbidden

```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 403,
  "error": "Forbidden",
  "message": "You are not authorized to update this post"
}
```

### 404 Not Found

```json
{
  "timestamp": "2024-01-15T10:30:00",
  "status": 404,
  "error": "Not Found",
  "message": "Post not found with id: '999'"
}
```

---

## What Works Now After This Commit?

### Full REST API Available:

```
┌─────────────────────────────────────────────────────────┐
│                    Controller Layer                      │
│         PostController (11 endpoints)                    │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    Service Layer                         │
│  PostService │ CategoryService │ TagService              │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                   Repository Layer                       │
│  PostRepository │ CategoryRepository │ TagRepository     │
└─────────────────────────────────────────────────────────┘
```

### Can Now Test With Postman:

- Create posts (with JWT token)
- List posts (see N+1 in action!)
- Get single posts
- Update posts (author only)
- Delete posts (author only)
- Search posts
- Filter by category/tag

### Still Has Issues:

- **N+1 query problem** - Will optimize in next commit
- **No CategoryController** - Coming soon
- **No TagController** - Coming soon

---

## Architectural Decisions Record

**ADR-046: URL Path Versioning**
- Status: Accepted
- Context: Need API versioning strategy
- Decision: Use `/api/v1/` prefix in URL path
- Consequences: Clear versioning, easy to test, slight URL pollution

**ADR-047: Method-Level Security with @PreAuthorize**
- Status: Accepted
- Context: Need role-based access control
- Decision: Use @PreAuthorize annotations on controller methods
- Consequences: Fine-grained control, requires @EnableMethodSecurity

**ADR-048: Standard Pagination Parameters**
- Status: Accepted
- Context: Need consistent pagination across endpoints
- Decision: Use page, size, sortBy, sortDir query parameters
- Consequences: Predictable API, Spring Page response format

**ADR-049: Enable Hibernate Statistics for Teaching**
- Status: Accepted (temporary)
- Context: Need to demonstrate N+1 problem
- Decision: Enable query statistics and SQL logging
- Consequences: Verbose logs, clear demonstration of N+1

---

## Commit Stats

- **Files created:** 1
    - PostController.java (220 lines)
- **Files modified:** 2
    - SecurityConfig.java (added @EnableMethodSecurity)
    - application.properties (added Hibernate statistics)
- **Endpoints created:** 11
- **Known issues:** N+1 queries (intentional, will fix)

---

## Next Steps

After this commit, we should:
1. **Fix N+1 problem** - Add JOIN FETCH queries
2. **Create CategoryController** - CRUD endpoints for categories
3. **Create TagController** - CRUD endpoints for tags
4. **Create Postman Collection** - Export and document

---

## Resources for Students

- Spring @PreAuthorize: https://www.baeldung.com/spring-security-method-security
- REST API Best Practices: https://www.vinaysahni.com/best-practices-for-a-pragmatic-restful-api
- Spring Pagination: https://www.baeldung.com/spring-data-jpa-pagination-sorting
- N+1 Problem: https://vladmihalcea.com/n-plus-1-query-problem/
- Postman Testing: https://learning.postman.com/docs/getting-started/introduction/
