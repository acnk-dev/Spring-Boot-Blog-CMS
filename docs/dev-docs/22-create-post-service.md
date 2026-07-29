# Commit 22: Create PostService with Basic CRUD (N+1 Problem Present)

**Date:** February 12, 2026
**Commit Message:** Create PostService for post management - basic implementation

---

## Development Mindset & Decision Process

### Why PostService Now?

**Current State:**
- All entities created
- Repositories ready
- DTOs defined
- Mappers implemented
- But no business logic layer!

**Goal:**
- Create PostService interface and implementation
- Implement CRUD operations for posts
- Add slug generation utility
- Handle authorization (only author can edit/delete)
- **Accept N+1 problem for now** (will optimize later)

**Teaching Point:** The service layer contains business logic. It orchestrates multiple repositories, enforces business rules, and transforms data. Never put business logic in controllers or repositories.

---

## Decision 1: Service Interface Pattern

### Why Interface + Implementation?

```java
// Interface
public interface PostService {
    PostResponse createPost(CreatePostRequest request, Long userId);
}

// Implementation
@Service
public class PostServiceImpl implements PostService {
    @Override
    public PostResponse createPost(CreatePostRequest request, Long userId) {
        // Actual implementation
    }
}
```

**Benefits:**

| Benefit | Description |
|---------|-------------|
| **Testability** | Easy to mock in unit tests |
| **Flexibility** | Can swap implementations |
| **Documentation** | Interface is the contract |
| **Decoupling** | Controller depends on interface, not implementation |

**Example: Multiple Implementations**
```java
@Service
@Profile("default")
public class PostServiceImpl implements PostService { }

@Service
@Profile("cache")
public class CachedPostServiceImpl implements PostService { }
```

---

## Decision 2: SlugUtil - URL-Friendly Identifiers

### What is a Slug?

```
Title: "Getting Started with Spring Boot!"
Slug:  "getting-started-with-spring-boot"
URL:   /posts/getting-started-with-spring-boot
```

### SlugUtil Implementation

```java
public class SlugUtil {

    public static String generateSlug(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(title, Normalizer.Form.NFD);
        String slug = WHITESPACE.matcher(normalized).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = MULTIPLE_HYPHENS.matcher(slug).replaceAll("-");
        slug = slug.toLowerCase(Locale.ENGLISH);
        slug = slug.replaceAll("^-|-$", "");

        return slug;
    }
}
```

### Slug Transformation Rules

| Input | Output | Rule Applied |
|-------|--------|--------------|
| `"Hello World"` | `"hello-world"` | Lowercase, space → hyphen |
| `"Spring Boot!"` | `"spring-boot"` | Remove special chars |
| `"C++ Guide"` | `"c-guide"` | Remove non-alphanumeric |
| `"  Multiple   Spaces  "` | `"multiple-spaces"` | Collapse whitespace |
| `"Café Programming"` | `"cafe-programming"` | Normalize Unicode |
| `"---Test---"` | `"test"` | Remove leading/trailing hyphens |

### Ensuring Unique Slugs

```java
public static String generateUniqueSlug(String title, PostRepository repository) {
    String baseSlug = generateSlug(title);
    String slug = baseSlug;
    int counter = 1;

    while (repository.existsBySlug(slug)) {
        slug = baseSlug + "-" + counter;
        counter++;
    }

    return slug;
}
```

**Example:**
```
Post 1: "Hello World" → "hello-world"
Post 2: "Hello World" → "hello-world-1"
Post 3: "Hello World" → "hello-world-2"
```

---

## Decision 3: Authorization Strategy

### Who Can Do What?

| Action | Who Can Perform |
|--------|-----------------|
| Create Post | Any authenticated user |
| Read Post | Anyone (if published) |
| Update Post | Only the author |
| Delete Post | Only the author |
| Publish Post | Only the author |

### Implementation

```java
@Override
@Transactional
public PostResponse updatePost(Long postId, UpdatePostRequest request, Long userId) {
    Post post = postRepository.findById(postId)
            .filter(p -> !p.getIsDeleted())
            .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

    // Authorization check
    if (!post.getUser().getId().equals(userId)) {
        throw new UnauthorizedException("You are not authorized to update this post");
    }

    // ... update logic
}
```

### Why Check in Service, Not Controller?

**Bad: Check in Controller**
```java
@PutMapping("/{id}")
public PostResponse updatePost(@PathVariable Long id, @RequestBody UpdatePostRequest request) {
    Post post = postService.getPostById(id);
    if (!post.getAuthorId().equals(getCurrentUserId())) {
        throw new UnauthorizedException();
    }
    return postService.updatePost(id, request);  // No userId passed
}
```

**Problems:**
- Business rule scattered across controllers
- Easy to forget in some endpoints
- Hard to test

**Good: Check in Service**
```java
@PutMapping("/{id}")
public PostResponse updatePost(@PathVariable Long id, @RequestBody UpdatePostRequest request) {
    Long userId = getCurrentUserId();
    return postService.updatePost(id, request, userId);  // Service handles auth
}
```

**Benefits:**
- Business logic centralized
- Consistent enforcement
- Easier to test

---

## Decision 4: Soft Delete Implementation

### How Soft Delete Works

```java
@Override
@Transactional
public void deletePost(Long postId, Long userId) {
    Post post = postRepository.findById(postId)
            .filter(p -> !p.getIsDeleted())  // Don't find already deleted
            .orElseThrow(() -> new ResourceNotFoundException("Post", "id", postId));

    // Authorization check
    if (!post.getUser().getId().equals(userId)) {
        throw new UnauthorizedException("You are not authorized to delete this post");
    }

    // Soft delete - just set flag
    post.setIsDeleted(true);
    postRepository.save(post);
}
```

### Why Not Hard Delete?

```java
// Hard delete
postRepository.deleteById(postId);
// Post is GONE forever!

// Soft delete
post.setIsDeleted(true);
postRepository.save(post);
// Post still in database, just hidden
```

**Soft delete benefits:**
- Recover accidentally deleted posts
- Maintain referential integrity
- Audit trail preserved
- SEO redirect capability

---

## Decision 5: The N+1 Problem (Intentionally Present)

### Where N+1 Occurs

```java
@Override
public Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir) {
    // Query 1: Fetch posts
    Page<Post> posts = postRepository.findByStatusAndIsDeletedFalse(
        PostStatus.PUBLISHED, pageable);

    // N+1 happens here!
    return posts.map(postMapper::toResponse);
}
```

### What Happens Inside postMapper::toResponse

```java
public PostResponse toResponse(Post post) {
    response.setAuthor(userMapper.toAuthorResponse(post.getUser()));     // Query 2, 3, 4...
    response.setCategory(categoryMapper.toResponse(post.getCategory())); // Query N+2, N+3...
    response.setTags(tagMapper.toResponseList(post.getTags()));          // Query 2N+2...
}
```

### Query Count Example

For 10 posts:
```
1 query:  SELECT * FROM posts WHERE status = 'PUBLISHED' AND is_deleted = false
10 queries: SELECT * FROM users WHERE id = ? (for each post's author)
10 queries: SELECT * FROM categories WHERE id = ? (for each post's category)
10 queries: SELECT * FROM post_tags pt JOIN tags t ON pt.tag_id = t.id WHERE pt.post_id = ?

Total: 31 queries for 10 posts!
```

### Why Ship With This Problem?

1. **Learning opportunity** - Students must understand N+1
2. **Clear optimization path** - Will fix in next commit
3. **Working code first** - Correct behavior before performance
4. **Measurable improvement** - Can show before/after

### Solutions (Coming in Next Commit)

```java
// Solution 1: JOIN FETCH
@Query("SELECT p FROM Post p " +
       "JOIN FETCH p.user " +
       "JOIN FETCH p.category " +
       "LEFT JOIN FETCH p.tags " +
       "WHERE p.status = :status AND p.isDeleted = false")
Page<Post> findAllWithRelations(@Param("status") PostStatus status, Pageable pageable);

// Solution 2: EntityGraph
@EntityGraph(attributePaths = {"user", "category", "tags"})
Page<Post> findByStatusAndIsDeletedFalse(PostStatus status, Pageable pageable);
```

---

## Files Created

### 1. SlugUtil.java

```java
public class SlugUtil {

    private static final Pattern NON_LATIN = Pattern.compile("[^\\w-]");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s]");
    private static final Pattern MULTIPLE_HYPHENS = Pattern.compile("-+");

    public static String generateSlug(String title) {
        if (title == null || title.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(title, Normalizer.Form.NFD);
        String slug = WHITESPACE.matcher(normalized).replaceAll("-");
        slug = NON_LATIN.matcher(slug).replaceAll("");
        slug = MULTIPLE_HYPHENS.matcher(slug).replaceAll("-");
        slug = slug.toLowerCase(Locale.ENGLISH);
        slug = slug.replaceAll("^-|-$", "");

        return slug;
    }

    public static String generateUniqueSlug(String title, PostRepository repository) {
        String baseSlug = generateSlug(title);
        if (baseSlug.isEmpty()) {
            baseSlug = "post";
        }

        String slug = baseSlug;
        int counter = 1;

        while (repository.existsBySlug(slug)) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        return slug;
    }

    public static String generateUniqueSlug(String title, String currentSlug, PostRepository repository) {
        String baseSlug = generateSlug(title);
        if (baseSlug.isEmpty()) {
            baseSlug = "post";
        }

        if (baseSlug.equals(currentSlug)) {
            return currentSlug;
        }

        String slug = baseSlug;
        int counter = 1;

        while (repository.existsBySlug(slug) && !slug.equals(currentSlug)) {
            slug = baseSlug + "-" + counter;
            counter++;
        }

        return slug;
    }
}
```

### 2. PostService.java (Interface)

```java
public interface PostService {

    PostResponse createPost(CreatePostRequest request, Long userId);

    PostResponse updatePost(Long postId, UpdatePostRequest request, Long userId);

    void deletePost(Long postId, Long userId);

    PostResponse getPostById(Long postId);

    PostResponse getPostBySlug(String slug);

    Page<PostResponse> getAllPosts(int page, int size, String sortBy, String sortDir);

    Page<PostResponse> getPostsByUser(Long userId, int page, int size);

    Page<PostResponse> searchPosts(String query, int page, int size);

    Page<PostResponse> getPostsByCategory(Long categoryId, int page, int size);

    List<PostResponse> getPostsByTag(Long tagId);

    void incrementViewCount(Long postId);

    PostResponse publishPost(Long postId, Long userId);
}
```

### 3. PostServiceImpl.java

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostServiceImpl implements PostService {

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final PostMapper postMapper;

    @Override
    @Transactional
    public PostResponse createPost(CreatePostRequest request, Long userId) {
        User author = userRepository.findById(userId)
                .filter(user -> !user.getIsDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        Category category = categoryRepository.findById(request.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", request.getCategoryId()));

        Set<Tag> tags = new HashSet<>();
        if (request.getTagIds() != null && !request.getTagIds().isEmpty()) {
            tags = new HashSet<>(tagRepository.findAllById(request.getTagIds()));
        }

        String slug = SlugUtil.generateUniqueSlug(request.getTitle(), postRepository);

        Post post = Post.builder()
                .user(author)
                .category(category)
                .title(request.getTitle())
                .slug(slug)
                .content(request.getContent())
                .excerpt(request.getExcerpt())
                .featuredImageUrl(request.getFeaturedImageUrl())
                .status(request.getStatus() != null ? request.getStatus() : PostStatus.DRAFT)
                .tags(tags)
                .build();

        if (post.getStatus() == PostStatus.PUBLISHED) {
            post.setPublishedAt(LocalDateTime.now());
        }

        Post savedPost = postRepository.save(post);
        return postMapper.toResponse(savedPost);
    }

    // ... other methods with N+1 problems documented
}
```

---

## Service Method Summary

| Method | Description | N+1 Problem? |
|--------|-------------|--------------|
| `createPost` | Create new post with auto-slug | No (single post) |
| `updatePost` | Update post (author only) | No (single post) |
| `deletePost` | Soft delete (author only) | No (no mapping) |
| `getPostById` | Get single post by ID | No (single post) |
| `getPostBySlug` | Get single post by slug | No (single post) |
| `getAllPosts` | Get paginated published posts | **YES** |
| `getPostsByUser` | Get author's posts | **YES** |
| `searchPosts` | Search by title/content | **YES** |
| `getPostsByCategory` | Get posts in category | **YES** |
| `getPostsByTag` | Get posts with tag | **YES** |
| `incrementViewCount` | Atomic counter update | No (direct SQL) |
| `publishPost` | Change status to published | No (single post) |

---

## Testing Status

### Can We Test with Postman Now?

**No, not yet!** We need a controller to expose REST endpoints.

**Current architecture:**
```
[Missing!]  →  PostService  →  PostRepository  →  Database
Controller      ✓ Created       ✓ Created         ✓ Ready
```

**What's needed:**
1. **PostController** - REST endpoints (`/api/posts`)
2. **Security configuration** - To get current user ID
3. **Authentication** - JWT token for protected endpoints

### What We Can Test Now

**Unit tests** (without Spring context):

```java
@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    @Mock
    private PostRepository postRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private PostMapper postMapper;

    @InjectMocks
    private PostServiceImpl postService;

    @Test
    void createPost_shouldGenerateSlug() {
        // Given
        Long userId = 1L;
        CreatePostRequest request = CreatePostRequest.builder()
                .title("Hello World")
                .content("Content here...")
                .categoryId(1L)
                .build();

        User author = User.builder().id(userId).build();
        Category category = Category.builder().id(1L).build();
        Post savedPost = Post.builder()
                .id(1L)
                .title("Hello World")
                .slug("hello-world")
                .build();
        PostResponse response = new PostResponse();

        when(userRepository.findById(userId)).thenReturn(Optional.of(author));
        when(categoryRepository.findById(1L)).thenReturn(Optional.of(category));
        when(postRepository.existsBySlug("hello-world")).thenReturn(false);
        when(postRepository.save(any(Post.class))).thenReturn(savedPost);
        when(postMapper.toResponse(savedPost)).thenReturn(response);

        // When
        PostResponse result = postService.createPost(request, userId);

        // Then
        assertNotNull(result);
        verify(postRepository).save(argThat(post ->
            post.getSlug().equals("hello-world")
        ));
    }

    @Test
    void updatePost_shouldThrowUnauthorized_whenNotAuthor() {
        // Given
        Long postId = 1L;
        Long userId = 2L;  // Different from author
        UpdatePostRequest request = new UpdatePostRequest();

        User author = User.builder().id(1L).build();  // Author is user 1
        Post post = Post.builder()
                .id(postId)
                .user(author)
                .isDeleted(false)
                .build();

        when(postRepository.findById(postId)).thenReturn(Optional.of(post));

        // When & Then
        assertThrows(UnauthorizedException.class, () ->
            postService.updatePost(postId, request, userId)
        );
    }
}
```

**Integration tests** (with Spring context):

```java
@SpringBootTest
@Transactional
class PostServiceIntegrationTest {

    @Autowired
    private PostService postService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void createPost_integrationTest() {
        // Setup test data
        User author = userRepository.save(User.builder()
                .email("test@test.com")
                .password("password")
                .name("Test User")
                .role(Role.AUTHOR)
                .build());

        Category category = categoryRepository.save(Category.builder()
                .name("Technology")
                .slug("technology")
                .build());

        CreatePostRequest request = CreatePostRequest.builder()
                .title("Integration Test Post")
                .content("This is a test content that is at least 50 characters long for validation.")
                .categoryId(category.getId())
                .build();

        // Execute
        PostResponse response = postService.createPost(request, author.getId());

        // Verify
        assertNotNull(response.getId());
        assertEquals("integration-test-post", response.getSlug());
        assertEquals(PostStatus.DRAFT, response.getStatus());
    }
}
```

---

## Postman Testing (After Controller is Created)

Once `PostController` is created, you'll be able to test with Postman:

### 1. Create Post

```http
POST /api/posts
Authorization: Bearer {{jwt_token}}
Content-Type: application/json

{
  "title": "Getting Started with Spring Boot",
  "content": "Spring Boot makes it easy to create stand-alone, production-grade Spring applications...",
  "excerpt": "Learn the basics of Spring Boot",
  "categoryId": 1,
  "tagIds": [1, 2, 3],
  "status": "DRAFT"
}
```

**Expected Response:**
```json
{
  "id": 1,
  "title": "Getting Started with Spring Boot",
  "slug": "getting-started-with-spring-boot",
  "content": "Spring Boot makes it easy...",
  "excerpt": "Learn the basics of Spring Boot",
  "status": "DRAFT",
  "viewCount": 0,
  "likeCount": 0,
  "author": {
    "id": 1,
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

### 2. Get All Posts (Paginated)

```http
GET /api/posts?page=0&size=10&sortBy=publishedAt&sortDir=desc
```

**Expected Response:**
```json
{
  "content": [
    { "id": 1, "title": "...", ... },
    { "id": 2, "title": "...", ... }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10
  },
  "totalElements": 25,
  "totalPages": 3,
  "last": false,
  "first": true
}
```

### 3. Get Post by Slug

```http
GET /api/posts/slug/getting-started-with-spring-boot
```

### 4. Update Post

```http
PUT /api/posts/1
Authorization: Bearer {{jwt_token}}
Content-Type: application/json

{
  "title": "Updated Title",
  "content": "Updated content..."
}
```

### 5. Delete Post (Soft Delete)

```http
DELETE /api/posts/1
Authorization: Bearer {{jwt_token}}
```

**Expected Response:** `204 No Content`

### 6. Search Posts

```http
GET /api/posts/search?query=spring&page=0&size=10
```

### 7. Get Posts by Category

```http
GET /api/posts/category/1?page=0&size=10
```

### 8. Publish Post

```http
POST /api/posts/1/publish
Authorization: Bearer {{jwt_token}}
```

---

## What Works Now After This Commit?

### Service Layer Complete:

```
                    PostService (Interface)
                           │
                           ▼
                    PostServiceImpl
                    ┌──────────────────────────────────┐
                    │  - PostRepository                │
                    │  - UserRepository                │
                    │  - CategoryRepository            │
                    │  - TagRepository                 │
                    │  - PostMapper                    │
                    │  - SlugUtil                      │
                    └──────────────────────────────────┘
```

### Business Logic Available:

```java
// In any component that needs post operations
@Autowired
private PostService postService;

// Create post
PostResponse created = postService.createPost(request, userId);

// Get posts with pagination
Page<PostResponse> posts = postService.getAllPosts(0, 10, "publishedAt", "desc");

// Search
Page<PostResponse> results = postService.searchPosts("spring boot", 0, 10);
```

### Still Needed:

- **PostController** - REST endpoints
- **N+1 optimization** - JOIN FETCH queries
- **CategoryService, TagService** - For complete CRUD
- **Postman collection** - After controller is created

---

## Architectural Decisions Record

**ADR-038: Interface + Implementation Pattern for Services**
- Status: Accepted
- Context: Need to define service layer contract
- Decision: Create PostService interface with PostServiceImpl
- Consequences: Better testability, clear contract, slight overhead

**ADR-039: Authorization in Service Layer**
- Status: Accepted
- Context: Need to enforce "only author can edit" rule
- Decision: Check authorization in service methods, pass userId from controller
- Consequences: Centralized business rules, testable, consistent

**ADR-040: Utility Class for Slug Generation**
- Status: Accepted
- Context: Need to generate URL-friendly slugs
- Decision: Static utility class (SlugUtil) rather than service
- Consequences: Simple, no DI needed, reusable across services

**ADR-041: Accept N+1 in Initial Implementation**
- Status: Accepted (temporary)
- Context: PostServiceImpl causes N+1 queries
- Decision: Ship with N+1, optimize in next commit
- Consequences: Working code first, clear optimization path, learning opportunity

---

## Commit Stats

- **Files created:** 3
    - SlugUtil.java (95 lines)
    - PostService.java (95 lines)
    - PostServiceImpl.java (220 lines)
- **Total lines:** ~410
- **Methods defined:** 12 service methods
- **Known issues:** N+1 queries (intentional, will fix)

---

## Next Steps

After this commit, we should:
1. **Create PostController** - REST endpoints for posts
2. **Optimize N+1** - Add JOIN FETCH queries
3. **Create CategoryService** - Category management
4. **Create TagService** - Tag management
5. **Create Postman Collection** - API testing

---

## Resources for Students

- Service Layer Pattern: https://www.baeldung.com/spring-service-layer
- Soft Delete: https://www.baeldung.com/spring-jpa-soft-delete
- URL Slugs: https://www.semrush.com/blog/what-is-a-url-slug/
- N+1 Problem: https://vladmihalcea.com/n-plus-1-query-problem/
- Spring @Transactional: https://www.baeldung.com/transaction-configuration-with-jpa-and-spring