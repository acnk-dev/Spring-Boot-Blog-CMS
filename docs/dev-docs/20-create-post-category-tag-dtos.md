# Commit 20: Create DTOs for Posts/Categories/Tags

**Date:** February 12, 2026
**Commit Message:** Create DTOs for Post, Category, and Tag operations

---

## Development Mindset & Decision Process

### Why DTOs Now?

**Current State:**
- All entities created (User, Category, Tag, Post)
- Repositories ready for database operations
- But exposing entities directly is dangerous!

**Goal:**
- Create Data Transfer Objects (DTOs) for API communication
- Separate internal data model from external API contract
- Add input validation
- Control what data is exposed

**Teaching Point:** DTOs are the API contract. Entities are the database contract. Keeping them separate is crucial for maintainable, secure applications.

---

## Why the DTO Pattern? (Industry Best Practice)

### The Problem: Exposing Entities Directly

```java
// BAD: Returning entity directly
@GetMapping("/posts/{id}")
public Post getPost(@PathVariable Long id) {
    return postRepository.findById(id).orElseThrow();
}
```

**What goes wrong:**

#### 1. Security Risk - Exposing Sensitive Data

```java
// Entity has sensitive fields
public class User {
    private String password;      // Exposed in JSON!
    private Boolean isDeleted;    // Internal flag exposed!
    private String internalNotes; // Admin-only data exposed!
}
```

**JSON Response (BAD):**
```json
{
  "id": 1,
  "email": "john@example.com",
  "password": "$2a$10$encrypted...",  // SECURITY BREACH!
  "isDeleted": false,
  "internalNotes": "VIP customer"
}
```

#### 2. Circular Reference - Infinite Loop

```java
public class Post {
    private User author;  // Post → User
}

public class User {
    private List<Post> posts;  // User → Posts → User → Posts → ...
}
```

**Result:** `StackOverflowError` or infinite JSON

#### 3. N+1 Query Problem - Performance Nightmare

```java
// Lazy-loaded relationships trigger queries during serialization
@GetMapping("/posts")
public List<Post> getAllPosts() {
    List<Post> posts = postRepository.findAll();
    // Jackson serializes posts...
    // For each post, loads author (N queries)
    // For each post, loads category (N queries)
    // For each post, loads tags (N queries)
    // 1 + 3N queries for N posts!
}
```

#### 4. API Contract Coupling - Breaking Changes

```java
// If you rename entity field...
public class Post {
    private String title;  // Renamed to "headline"
}

// All API consumers break!
// Frontend code breaks!
// Mobile apps break!
// Third-party integrations break!
```

#### 5. Over-fetching - Wasted Bandwidth

```java
// List view only needs title and author
// But entity returns EVERYTHING
{
  "id": 1,
  "title": "My Post",
  "content": "... 50KB of HTML content ...",  // Not needed for list!
  "author": { ... full user object ... },
  "category": { ... },
  "tags": [ ... ],
  // 100+ fields when you only need 5
}
```

### The Solution: DTO Pattern

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│    DTO      │────▶│   Entity    │
│  (Request)  │     │  (Request)  │     │  (Database) │
└─────────────┘     └─────────────┘     └─────────────┘

┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │◀────│    DTO      │◀────│   Entity    │
│  (Response) │     │  (Response) │     │  (Database) │
└─────────────┘     └─────────────┘     └─────────────┘
```

**Benefits:**

| Benefit | Description |
|---------|-------------|
| **Security** | Only expose what you intend to |
| **Decoupling** | Entity changes don't break API |
| **Validation** | Input validation on request DTOs |
| **Performance** | Control exactly what data is loaded |
| **Versioning** | Can have V1 and V2 DTOs simultaneously |
| **Documentation** | DTOs clearly document API contract |

### Industry Adoption

**Companies using DTO pattern:**
- **Netflix** - Separate DTOs for each client (web, mobile, TV)
- **Spotify** - DTOs for different API versions
- **Uber** - DTOs with validation for ride requests
- **Amazon** - DTOs for AWS SDK responses
- **Every enterprise Java application**

**Frameworks that encourage DTOs:**
- Spring Boot (recommended in official docs)
- .NET Core (with AutoMapper)
- NestJS (with class-validator)
- Django REST Framework (Serializers = DTOs)

**Teaching Point:** DTOs aren't optional in professional development. They're a fundamental pattern that every production application uses.

---

## Decision 1: Request vs Response DTOs

### Why Separate Request and Response?

**Different purposes:**

| Request DTO | Response DTO |
|-------------|--------------|
| Input validation | Output formatting |
| What client sends | What client receives |
| May have fewer fields | May have computed fields |
| Has @NotBlank, @Size, etc. | No validation needed |
| Never has ID (for create) | Always has ID |

### Example: CreatePostRequest vs PostResponse

**CreatePostRequest (Input):**
```java
public class CreatePostRequest {
    private String title;       // Client provides
    private String content;     // Client provides
    private Long categoryId;    // Client provides ID
    private List<Long> tagIds;  // Client provides IDs
    // NO id, slug, viewCount, createdAt - server generates these
}
```

**PostResponse (Output):**
```java
public class PostResponse {
    private Long id;            // Server generated
    private String title;       // From entity
    private String slug;        // Server generated
    private Integer viewCount;  // Server tracked
    private AuthorResponse author;  // Nested object, not just ID
    private CategoryResponse category;  // Full category, not just ID
    private List<TagResponse> tags;  // Full tags, not just IDs
    private LocalDateTime createdAt;  // Server generated
}
```

**Teaching Point:** Request DTOs take IDs (references), Response DTOs return full objects (data).

---

## Decision 2: Validation Strategy

### Jakarta Validation Annotations

```java
public class CreatePostRequest {

    @NotBlank(message = "Title is required")
    @Size(min = 5, max = 200, message = "Title must be between 5 and 200 characters")
    private String title;

    @NotBlank(message = "Content is required")
    @Size(min = 50, message = "Content must be at least 50 characters")
    private String content;

    @Size(max = 500, message = "Excerpt cannot exceed 500 characters")
    private String excerpt;

    @NotNull(message = "Category is required")
    private Long categoryId;
}
```

### Common Validation Annotations

| Annotation | Purpose | Example |
|------------|---------|---------|
| `@NotNull` | Field cannot be null | `@NotNull Long categoryId` |
| `@NotBlank` | String not null/empty/whitespace | `@NotBlank String title` |
| `@NotEmpty` | Collection not null/empty | `@NotEmpty List<Long> ids` |
| `@Size` | Length constraints | `@Size(min=5, max=200)` |
| `@Min` / `@Max` | Numeric range | `@Min(0) Integer count` |
| `@Email` | Valid email format | `@Email String email` |
| `@Pattern` | Regex pattern | `@Pattern(regexp="^[a-z]+$")` |
| `@Past` / `@Future` | Date constraints | `@Past LocalDate birthDate` |

### Why Validate at DTO Level?

**Fail fast principle:**
```java
// BAD: Validation in service (too late)
public Post createPost(CreatePostRequest request) {
    if (request.getTitle() == null) {  // Already in service layer
        throw new ValidationException("Title required");
    }
}

// GOOD: Validation at controller (fail immediately)
@PostMapping("/posts")
public PostResponse createPost(@Valid @RequestBody CreatePostRequest request) {
    // Request already validated before reaching here
    // If invalid, Spring returns 400 Bad Request automatically
}
```

### Custom Error Messages

```java
@NotBlank(message = "Title is required")
@Size(min = 5, max = 200, message = "Title must be between 5 and 200 characters")
private String title;
```

**API Response (validation failure):**
```json
{
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

---

## Decision 3: Update DTO Design

### CreatePostRequest vs UpdatePostRequest

**CreatePostRequest - All required fields:**
```java
public class CreatePostRequest {
    @NotBlank private String title;      // Required
    @NotBlank private String content;    // Required
    @NotNull private Long categoryId;    // Required
    private PostStatus status = PostStatus.DRAFT;  // Default
}
```

**UpdatePostRequest - All optional fields:**
```java
public class UpdatePostRequest {
    @Size(min = 5, max = 200)
    private String title;      // Optional - only update if provided

    @Size(min = 50)
    private String content;    // Optional

    private Long categoryId;   // Optional
    private PostStatus status; // Optional
}
```

### Partial Updates (PATCH semantics)

```java
// In service layer
public Post updatePost(Long id, UpdatePostRequest request) {
    Post post = postRepository.findById(id).orElseThrow();

    // Only update fields that were provided
    if (request.getTitle() != null) {
        post.setTitle(request.getTitle());
    }
    if (request.getContent() != null) {
        post.setContent(request.getContent());
    }
    if (request.getCategoryId() != null) {
        Category category = categoryRepository.findById(request.getCategoryId())
            .orElseThrow();
        post.setCategory(category);
    }
    // ... etc

    return postRepository.save(post);
}
```

**Why partial updates?**
- Client only sends changed fields
- Less bandwidth
- Less chance of overwriting concurrent changes
- Better UX (edit single field without loading entire form)

---

## Decision 4: Response DTO Nesting

### Nested Objects vs IDs

**Option 1: Return IDs only (Bad for frontend)**
```json
{
  "id": 1,
  "title": "My Post",
  "authorId": 5,
  "categoryId": 3,
  "tagIds": [1, 2, 3]
}
```
Frontend must make 4+ additional API calls to get author, category, tags!

**Option 2: Return nested objects (Our choice)**
```json
{
  "id": 1,
  "title": "My Post",
  "author": {
    "id": 5,
    "name": "John Doe",
    "avatarUrl": "https://..."
  },
  "category": {
    "id": 3,
    "name": "Technology",
    "slug": "technology"
  },
  "tags": [
    { "id": 1, "name": "java", "slug": "java" },
    { "id": 2, "name": "spring", "slug": "spring" }
  ]
}
```
Frontend has everything needed in one request!

### AuthorResponse - Minimal User Data

```java
public class AuthorResponse {
    private Long id;
    private String name;
    private String avatarUrl;
    // NOT email, password, role, bio, etc.
}
```

**Why not full UserResponse?**
- Don't need email in post listing
- Don't need role information
- Smaller payload
- Principle of least privilege

**Teaching Point:** Create purpose-specific DTOs. AuthorResponse for post context, UserResponse for profile context.

---

## Decision 5: fromEntity() Static Method Pattern

### The Conversion Method

```java
public class PostResponse {
    // ... fields ...

    public static PostResponse fromEntity(Post post) {
        if (post == null) {
            return null;
        }
        return PostResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .slug(post.getSlug())
                .author(AuthorResponse.fromEntity(post.getUser()))
                .category(CategoryResponse.fromEntity(post.getCategory()))
                .tags(post.getTags().stream()
                        .map(TagResponse::fromEntity)
                        .collect(Collectors.toList()))
                .build();
    }
}
```

### Why Static Factory Method?

**Alternative 1: Constructor**
```java
public PostResponse(Post post) {
    this.id = post.getId();
    this.title = post.getTitle();
    // ...
}
// Problem: Can't return null, verbose for optional fields
```

**Alternative 2: Separate Mapper Class**
```java
@Component
public class PostMapper {
    public PostResponse toResponse(Post post) { ... }
}
// Problem: Another class to maintain, needs injection
```

**Alternative 3: MapStruct (Popular in enterprise)**
```java
@Mapper
public interface PostMapper {
    PostResponse toResponse(Post post);
}
// Good for complex mappings, but overkill for simple cases
```

**Our choice: Static factory method**
```java
PostResponse response = PostResponse.fromEntity(post);
```
- Simple and readable
- No extra dependencies
- Null-safe
- Co-located with DTO (easy to find)

### Null Safety

```java
public static CategoryResponse fromEntity(Category category) {
    if (category == null) {
        return null;  // Handle optional relationships
    }
    return CategoryResponse.builder()
            // ...
            .build();
}
```

**Why null check?**
- Post.category is optional (nullable)
- Avoids NullPointerException
- Returns null in JSON (or can be excluded)

---

## Files Created

### Request DTOs

#### 1. CreatePostRequest.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePostRequest {

    @NotBlank(message = "Title is required")
    @Size(min = 5, max = 200, message = "Title must be between 5 and 200 characters")
    private String title;

    @NotBlank(message = "Content is required")
    @Size(min = 50, message = "Content must be at least 50 characters")
    private String content;

    @Size(max = 500, message = "Excerpt cannot exceed 500 characters")
    private String excerpt;

    private String featuredImageUrl;

    @NotNull(message = "Category is required")
    private Long categoryId;

    @Builder.Default
    private List<Long> tagIds = new ArrayList<>();

    @Builder.Default
    private PostStatus status = PostStatus.DRAFT;
}
```

#### 2. UpdatePostRequest.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePostRequest {

    @Size(min = 5, max = 200, message = "Title must be between 5 and 200 characters")
    private String title;

    @Size(min = 50, message = "Content must be at least 50 characters")
    private String content;

    @Size(max = 500, message = "Excerpt cannot exceed 500 characters")
    private String excerpt;

    private String featuredImageUrl;
    private Long categoryId;
    private List<Long> tagIds;
    private PostStatus status;
}
```

#### 3. CreateCategoryRequest.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCategoryRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
    private String name;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;
}
```

#### 4. CreateTagRequest.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTagRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 2, max = 50, message = "Name must be between 2 and 50 characters")
    private String name;
}
```

### Response DTOs

#### 5. PostResponse.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostResponse {

    private Long id;
    private String title;
    private String slug;
    private String content;
    private String excerpt;
    private String featuredImageUrl;
    private PostStatus status;
    private Integer viewCount;
    private Integer likeCount;
    private AuthorResponse author;
    private CategoryResponse category;
    private List<TagResponse> tags;
    private LocalDateTime publishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static PostResponse fromEntity(Post post) {
        if (post == null) return null;
        return PostResponse.builder()
                .id(post.getId())
                .title(post.getTitle())
                .slug(post.getSlug())
                .content(post.getContent())
                .excerpt(post.getExcerpt())
                .featuredImageUrl(post.getFeaturedImageUrl())
                .status(post.getStatus())
                .viewCount(post.getViewCount())
                .likeCount(post.getLikeCount())
                .author(AuthorResponse.fromEntity(post.getUser()))
                .category(CategoryResponse.fromEntity(post.getCategory()))
                .tags(post.getTags().stream()
                        .map(TagResponse::fromEntity)
                        .collect(Collectors.toList()))
                .publishedAt(post.getPublishedAt())
                .createdAt(post.getCreatedAt())
                .updatedAt(post.getUpdatedAt())
                .build();
    }
}
```

#### 6. AuthorResponse.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthorResponse {

    private Long id;
    private String name;
    private String avatarUrl;

    public static AuthorResponse fromEntity(User user) {
        if (user == null) return null;
        return AuthorResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }
}
```

#### 7. CategoryResponse.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryResponse {

    private Long id;
    private String name;
    private String slug;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static CategoryResponse fromEntity(Category category) {
        if (category == null) return null;
        return CategoryResponse.builder()
                .id(category.getId())
                .name(category.getName())
                .slug(category.getSlug())
                .description(category.getDescription())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .build();
    }
}
```

#### 8. TagResponse.java

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TagResponse {

    private Long id;
    private String name;
    private String slug;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static TagResponse fromEntity(Tag tag) {
        if (tag == null) return null;
        return TagResponse.builder()
                .id(tag.getId())
                .name(tag.getName())
                .slug(tag.getSlug())
                .createdAt(tag.getCreatedAt())
                .updatedAt(tag.getUpdatedAt())
                .build();
    }
}
```

---

## DTO Design Patterns Summary

### Pattern 1: Request/Response Separation

```
CreatePostRequest  →  Service  →  PostResponse
UpdatePostRequest  →  Service  →  PostResponse
                      ↓
                   Post (Entity)
```

### Pattern 2: Nested Response Objects

```
PostResponse
├── AuthorResponse (simplified User)
├── CategoryResponse
└── List<TagResponse>
```

### Pattern 3: Validation on Input Only

```java
// Request DTO - has validation
public class CreatePostRequest {
    @NotBlank private String title;
}

// Response DTO - no validation needed
public class PostResponse {
    private String title;  // Just data
}
```

### Pattern 4: Static Factory for Conversion

```java
// In each response DTO
public static XxxResponse fromEntity(Xxx entity) {
    return XxxResponse.builder()
        .field(entity.getField())
        .build();
}
```

---

## What Works Now After This Commit?

### API Contract Defined:

**Create Post:**
```json
// Request
POST /api/posts
{
  "title": "Getting Started with Spring Boot",
  "content": "Spring Boot makes Java development...",
  "excerpt": "Learn Spring Boot basics",
  "categoryId": 1,
  "tagIds": [1, 2, 3],
  "status": "DRAFT"
}

// Response
{
  "id": 1,
  "title": "Getting Started with Spring Boot",
  "slug": "getting-started-with-spring-boot",
  "content": "Spring Boot makes Java development...",
  "excerpt": "Learn Spring Boot basics",
  "status": "DRAFT",
  "viewCount": 0,
  "likeCount": 0,
  "author": {
    "id": 5,
    "name": "John Doe",
    "avatarUrl": null
  },
  "category": {
    "id": 1,
    "name": "Technology",
    "slug": "technology"
  },
  "tags": [
    { "id": 1, "name": "java", "slug": "java" },
    { "id": 2, "name": "spring", "slug": "spring" }
  ],
  "publishedAt": null,
  "createdAt": "2024-01-15T10:30:00",
  "updatedAt": "2024-01-15T10:30:00"
}
```

### Still Needed:

- **SlugService** - Generate slugs from titles
- **PostService** - Business logic using these DTOs
- **CategoryService** - Category management
- **TagService** - Tag management
- **Controllers** - REST endpoints

---

## Common DTO Mistakes to Avoid

### 1. Using Entity as DTO

```java
// BAD
@PostMapping("/posts")
public Post createPost(@RequestBody Post post) { ... }

// GOOD
@PostMapping("/posts")
public PostResponse createPost(@RequestBody CreatePostRequest request) { ... }
```

### 2. Forgetting @Valid

```java
// BAD - validation annotations ignored!
@PostMapping("/posts")
public PostResponse createPost(@RequestBody CreatePostRequest request) { ... }

// GOOD - validation triggered
@PostMapping("/posts")
public PostResponse createPost(@Valid @RequestBody CreatePostRequest request) { ... }
```

### 3. Returning Entity from Service

```java
// BAD - leaks entity to controller
public Post createPost(CreatePostRequest request) {
    return postRepository.save(post);
}

// GOOD - service returns DTO
public PostResponse createPost(CreatePostRequest request) {
    Post post = postRepository.save(newPost);
    return PostResponse.fromEntity(post);
}
```

### 4. Over-nesting DTOs

```java
// BAD - too deep
PostResponse
└── AuthorResponse
    └── AddressResponse
        └── CityResponse
            └── CountryResponse

// GOOD - flatten when appropriate
PostResponse
└── AuthorResponse (id, name, avatarUrl only)
```

---

## Architectural Decisions Record

**ADR-030: Use DTO Pattern for API Communication**
- Status: Accepted
- Context: Need to define API contracts separate from database model
- Decision: Create separate Request and Response DTOs
- Consequences: More classes, but better security, decoupling, and validation

**ADR-031: Separate Create and Update DTOs**
- Status: Accepted
- Context: Create requires all fields, Update allows partial
- Decision: CreateXxxRequest (required fields) vs UpdateXxxRequest (optional fields)
- Consequences: Clear semantics, proper validation for each use case

**ADR-032: Use Static fromEntity() for Conversion**
- Status: Accepted
- Context: Need to convert entities to DTOs
- Decision: Static factory method in each response DTO
- Consequences: Simple, no extra dependencies, co-located with DTO

**ADR-033: Nest Related Objects in Response DTOs**
- Status: Accepted
- Context: Decide between returning IDs or full objects
- Decision: Return nested objects (AuthorResponse, CategoryResponse, etc.)
- Consequences: Single request gets all data, slightly larger payload

---

## Commit Stats

- **Files created:** 8
    - CreatePostRequest.java (36 lines)
    - UpdatePostRequest.java (28 lines)
    - CreateCategoryRequest.java (18 lines)
    - CreateTagRequest.java (16 lines)
    - PostResponse.java (52 lines)
    - AuthorResponse.java (24 lines)
    - CategoryResponse.java (32 lines)
    - TagResponse.java (30 lines)
- **Total lines:** ~236
- **Validation annotations used:** 12

---

## Next Steps

After this commit, we should:
1. **Create SlugService** - Generate URL-friendly slugs
2. **Create PostService** - CRUD operations using DTOs
3. **Create CategoryService** - Category management
4. **Create TagService** - Tag management
5. **Create Controllers** - REST endpoints with validation

---

## Resources for Students

- DTO Pattern: https://martinfowler.com/eaaCatalog/dataTransferObject.html
- Jakarta Validation: https://beanvalidation.org/2.0/spec/
- Spring Validation: https://www.baeldung.com/spring-boot-bean-validation
- MapStruct (alternative): https://mapstruct.org/
- Why DTOs: https://www.baeldung.com/java-dto-pattern
