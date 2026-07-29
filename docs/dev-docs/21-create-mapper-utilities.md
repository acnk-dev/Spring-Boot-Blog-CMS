# Commit 21: Create Mapper Utilities for Entity-DTO Conversion

**Date:** February 12, 2026
**Commit Message:** Create mapper utilities for Entity to DTO conversion

---

## Development Mindset & Decision Process

### Why Mappers Now?

**Current State:**
- DTOs created for request/response
- DTOs have static `fromEntity()` methods
- But we need reusable, injectable mapping logic

**Goal:**
- Create dedicated mapper classes
- Enable dependency injection for complex mappings
- Prepare for N+1 optimization later
- Centralize conversion logic

**Teaching Point:** Mappers are the bridge between your data layer (entities) and your API layer (DTOs). Having dedicated mapper classes makes testing easier and allows for optimization later.

---

## Decision 1: Why Separate Mapper Classes?

### Option 1: Static Methods in DTOs (Current)

```java
public class PostResponse {
    public static PostResponse fromEntity(Post post) {
        // Conversion logic here
    }
}
```

**Pros:**
- Simple, co-located with DTO
- No extra classes

**Cons:**
- Can't inject dependencies
- Hard to mock in tests
- Can't share logic between DTOs

### Option 2: Dedicated Mapper Classes (Our Choice)

```java
@Component
public class PostMapper {
    @Autowired
    private UserMapper userMapper;

    public PostResponse toResponse(Post post) {
        // Can use other mappers
    }
}
```

**Pros:**
- Dependency injection works
- Easy to mock in tests
- Can compose mappers (PostMapper uses UserMapper)
- Can add caching, logging, etc.
- Easier to optimize (e.g., batch loading)

**Cons:**
- More classes
- Need to inject mapper in services

### Option 3: MapStruct (Popular Alternative)

```java
@Mapper(componentModel = "spring")
public interface PostMapper {
    PostResponse toResponse(Post post);
}
```

**Pros:**
- Generates code at compile time
- Very performant
- Less boilerplate

**Cons:**
- Another dependency
- Magic (generated code)
- Harder to debug
- Learning curve

**Our choice:** Manual mappers with @Component for learning purposes. MapStruct is great for production but hides the implementation details students should understand.

---

## Decision 2: Mapper Design Pattern

### The Structure

```
┌─────────────────────────────────────────────────────┐
│                    PostMapper                        │
│  ┌─────────────┐  ┌──────────────┐  ┌───────────┐  │
│  │ UserMapper  │  │CategoryMapper│  │ TagMapper │  │
│  └─────────────┘  └──────────────┘  └───────────┘  │
└─────────────────────────────────────────────────────┘
         │                  │                │
         ▼                  ▼                ▼
    AuthorResponse   CategoryResponse   TagResponse
```

### Composition Over Inheritance

```java
@Component
public class PostMapper {

    private final UserMapper userMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    @Autowired
    public PostMapper(UserMapper userMapper,
                      CategoryMapper categoryMapper,
                      TagMapper tagMapper) {
        this.userMapper = userMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
    }
}
```

**Why constructor injection?**
- Makes dependencies explicit
- Easier to test (pass mocks in constructor)
- Immutable (final fields)
- Fails fast if dependency missing

---

## Decision 3: Instance Methods + Static Methods

### Dual Approach

```java
@Component
public class UserMapper {

    // Instance method - for Spring injection
    public UserResponse toResponse(User user) {
        return toUserResponse(user);
    }

    // Static method - for convenience
    public static UserResponse toUserResponse(User user) {
        if (user == null) return null;
        return UserResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .build();
    }
}
```

### When to Use Which?

| Scenario | Use |
|----------|-----|
| In Service class | Instance method (injected mapper) |
| In DTO fromEntity() | Static method |
| In tests | Either (static simpler) |
| Complex mapping needing other mappers | Instance method |

**Teaching Point:** Static methods are convenient but can't use dependency injection. Instance methods enable DI but require the mapper to be injected.

---

## Decision 4: The N+1 Query Problem (Intentionally Not Solved)

### What is N+1?

```java
// 1 query: Get all posts
List<Post> posts = postRepository.findAll();

// N queries: For EACH post, load author
for (Post post : posts) {
    User author = post.getUser();  // Lazy load triggers query!
    // SELECT * FROM users WHERE id = ?
}
```

**With 100 posts:**
- 1 query for posts
- 100 queries for authors
- 100 queries for categories
- 100 queries for tags
- **Total: 301 queries!**

### Our PostMapper (Intentionally Causes N+1)

```java
public PostResponse toResponse(Post post) {
    // ...

    // WARNING: These calls trigger lazy loading!
    response.setAuthor(userMapper.toAuthorResponse(post.getUser()));     // Query!
    response.setCategory(categoryMapper.toResponse(post.getCategory())); // Query!
    response.setTags(tagMapper.toResponseList(post.getTags()));          // Query!

    return response;
}
```

### Why Not Fix It Now?

**Pedagogical reasons:**
1. Students should see the problem first
2. Understanding N+1 is crucial for backend developers
3. Fixing it requires understanding JOIN FETCH, EntityGraph
4. We'll fix it in the next commit (optimization)

### Solutions (Coming Later)

**Solution 1: JOIN FETCH in Repository**
```java
@Query("SELECT p FROM Post p " +
       "JOIN FETCH p.user " +
       "JOIN FETCH p.category " +
       "LEFT JOIN FETCH p.tags")
List<Post> findAllWithRelations();
```

**Solution 2: EntityGraph**
```java
@EntityGraph(attributePaths = {"user", "category", "tags"})
List<Post> findAll();
```

**Solution 3: Batch Size**
```java
@BatchSize(size = 25)
private Set<Tag> tags;
```

**Teaching Point:** Recognizing N+1 is a critical skill. We intentionally ship code with this problem so students learn to identify and fix it.

---

## Files Created

### 1. UserMapper.java

```java
@Component
public class UserMapper {

    /**
     * Convert User entity to full UserResponse DTO.
     * Used for profile pages and admin views.
     */
    public UserResponse toResponse(User user) {
        return toUserResponse(user);
    }

    /**
     * Convert User entity to AuthorResponse DTO.
     * Used for embedding author info in post responses.
     * Contains only public-facing fields (id, name, avatarUrl).
     */
    public AuthorResponse toAuthorResponse(User user) {
        return toAuthor(user);
    }

    /**
     * Convert list of User entities to UserResponse DTOs.
     */
    public List<UserResponse> toResponseList(List<User> users) {
        return users.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    // Static methods
    public static UserResponse toUserResponse(User user) {
        if (user == null) return null;
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .name(user.getName())
                .role(user.getRole())
                .emailVerified(user.getEmailVerified())
                .bio(user.getBio())
                .avatarUrl(user.getAvatarUrl())
                .createdAt(user.getCreatedAt())
                .build();
    }

    public static AuthorResponse toAuthor(User user) {
        if (user == null) return null;
        return AuthorResponse.builder()
                .id(user.getId())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }
}
```

### 2. CategoryMapper.java

```java
@Component
public class CategoryMapper {

    public CategoryResponse toResponse(Category category) {
        return toCategoryResponse(category);
    }

    public List<CategoryResponse> toResponseList(List<Category> categories) {
        return categories.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public static CategoryResponse toCategoryResponse(Category category) {
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

### 3. TagMapper.java

```java
@Component
public class TagMapper {

    public TagResponse toResponse(Tag tag) {
        return toTagResponse(tag);
    }

    public List<TagResponse> toResponseList(List<Tag> tags) {
        return tags.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<TagResponse> toResponseList(Set<Tag> tags) {
        return tags.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public List<TagResponse> toResponseList(Collection<Tag> tags) {
        return tags.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public static TagResponse toTagResponse(Tag tag) {
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

### 4. PostMapper.java

```java
/**
 * WARNING: This basic implementation will cause N+1 query problems!
 *
 * When mapping a list of posts:
 * - 1 query to fetch posts
 * - N queries to fetch each post's author (lazy loading)
 * - N queries to fetch each post's category (lazy loading)
 * - N queries to fetch each post's tags (lazy loading)
 *
 * Total: 1 + 3N queries for N posts!
 */
@Component
public class PostMapper {

    private final UserMapper userMapper;
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;

    @Autowired
    public PostMapper(UserMapper userMapper,
                      CategoryMapper categoryMapper,
                      TagMapper tagMapper) {
        this.userMapper = userMapper;
        this.categoryMapper = categoryMapper;
        this.tagMapper = tagMapper;
    }

    /**
     * Convert Post entity to PostResponse DTO.
     * WARNING: This triggers lazy loading of author, category, and tags!
     */
    public PostResponse toResponse(Post post) {
        if (post == null) return null;

        PostResponse response = new PostResponse();
        response.setId(post.getId());
        response.setTitle(post.getTitle());
        response.setSlug(post.getSlug());
        response.setContent(post.getContent());
        response.setExcerpt(post.getExcerpt());
        response.setFeaturedImageUrl(post.getFeaturedImageUrl());
        response.setStatus(post.getStatus());
        response.setViewCount(post.getViewCount());
        response.setLikeCount(post.getLikeCount());
        response.setPublishedAt(post.getPublishedAt());
        response.setCreatedAt(post.getCreatedAt());
        response.setUpdatedAt(post.getUpdatedAt());

        // WARNING: These calls trigger lazy loading!
        response.setAuthor(userMapper.toAuthorResponse(post.getUser()));
        response.setCategory(categoryMapper.toResponse(post.getCategory()));
        response.setTags(tagMapper.toResponseList(post.getTags()));

        return response;
    }

    /**
     * Convert list of Post entities to PostResponse DTOs.
     * WARNING: For N posts, this will execute up to 1 + 3N queries!
     */
    public List<PostResponse> toResponseList(List<Post> posts) {
        return posts.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Convert Post to PostResponse without loading relationships.
     * Use this when you only need basic post info.
     */
    public PostResponse toResponseWithoutRelations(Post post) {
        if (post == null) return null;

        PostResponse response = new PostResponse();
        response.setId(post.getId());
        response.setTitle(post.getTitle());
        response.setSlug(post.getSlug());
        response.setContent(post.getContent());
        response.setExcerpt(post.getExcerpt());
        response.setFeaturedImageUrl(post.getFeaturedImageUrl());
        response.setStatus(post.getStatus());
        response.setViewCount(post.getViewCount());
        response.setLikeCount(post.getLikeCount());
        response.setPublishedAt(post.getPublishedAt());
        response.setCreatedAt(post.getCreatedAt());
        response.setUpdatedAt(post.getUpdatedAt());
        // author, category, tags left as null

        return response;
    }
}
```

---

## Mapper Method Overview

### UserMapper

| Method | Returns | Description |
|--------|---------|-------------|
| `toResponse(User)` | `UserResponse` | Full user data |
| `toAuthorResponse(User)` | `AuthorResponse` | Minimal (id, name, avatar) |
| `toResponseList(List<User>)` | `List<UserResponse>` | Batch conversion |
| `toUserResponse(User)` (static) | `UserResponse` | No DI needed |
| `toAuthor(User)` (static) | `AuthorResponse` | No DI needed |

### CategoryMapper

| Method | Returns | Description |
|--------|---------|-------------|
| `toResponse(Category)` | `CategoryResponse` | Single category |
| `toResponseList(List<Category>)` | `List<CategoryResponse>` | Batch conversion |
| `toCategoryResponse(Category)` (static) | `CategoryResponse` | No DI needed |

### TagMapper

| Method | Returns | Description |
|--------|---------|-------------|
| `toResponse(Tag)` | `TagResponse` | Single tag |
| `toResponseList(List<Tag>)` | `List<TagResponse>` | From List |
| `toResponseList(Set<Tag>)` | `List<TagResponse>` | From Set (for Post.tags) |
| `toResponseList(Collection<Tag>)` | `List<TagResponse>` | Generic |
| `toTagResponse(Tag)` (static) | `TagResponse` | No DI needed |

### PostMapper

| Method | Returns | Description |
|--------|---------|-------------|
| `toResponse(Post)` | `PostResponse` | Full post with relations (N+1!) |
| `toResponseList(List<Post>)` | `List<PostResponse>` | Batch (N+1!) |
| `toResponseWithoutRelations(Post)` | `PostResponse` | No lazy loading |
| `toPostResponse(Post)` (static) | `PostResponse` | Uses static mappers |

---

## Usage Examples

### In Service Layer

```java
@Service
public class PostService {

    private final PostRepository postRepository;
    private final PostMapper postMapper;

    @Autowired
    public PostService(PostRepository postRepository, PostMapper postMapper) {
        this.postRepository = postRepository;
        this.postMapper = postMapper;
    }

    public PostResponse getPost(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new PostNotFoundException(id));
        return postMapper.toResponse(post);
    }

    public List<PostResponse> getAllPosts() {
        List<Post> posts = postRepository.findAll();
        return postMapper.toResponseList(posts);  // WARNING: N+1!
    }
}
```

### In Unit Tests

```java
@Test
void shouldMapPostToResponse() {
    // Using static method - no need for Spring context
    User author = User.builder().id(1L).name("John").build();
    Post post = Post.builder()
            .id(1L)
            .title("Test")
            .user(author)
            .build();

    PostResponse response = PostMapper.toPostResponse(post);

    assertEquals(1L, response.getId());
    assertEquals("Test", response.getTitle());
    assertEquals("John", response.getAuthor().getName());
}
```

### With Mocks

```java
@ExtendWith(MockitoExtension.class)
class PostMapperTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private CategoryMapper categoryMapper;

    @Mock
    private TagMapper tagMapper;

    @InjectMocks
    private PostMapper postMapper;

    @Test
    void shouldUseUserMapperForAuthor() {
        User author = new User();
        Post post = Post.builder().user(author).build();
        AuthorResponse authorResponse = new AuthorResponse();

        when(userMapper.toAuthorResponse(author)).thenReturn(authorResponse);

        PostResponse response = postMapper.toResponse(post);

        assertEquals(authorResponse, response.getAuthor());
        verify(userMapper).toAuthorResponse(author);
    }
}
```

---

## What Works Now After This Commit?

### Mapper Layer Complete:

```
Service Layer
     │
     ▼
┌─────────────────────────────────────┐
│           Mapper Layer              │
│  ┌──────────┐  ┌────────────────┐  │
│  │UserMapper│  │  PostMapper    │  │
│  └──────────┘  │  ┌──────────┐  │  │
│  ┌──────────┐  │  │UserMapper│  │  │
│  │ Category │  │  │Category  │  │  │
│  │  Mapper  │  │  │TagMapper │  │  │
│  └──────────┘  │  └──────────┘  │  │
│  ┌──────────┐  └────────────────┘  │
│  │TagMapper │                      │
│  └──────────┘                      │
└─────────────────────────────────────┘
     │
     ▼
DTO Layer (Response objects)
```

### Can Now:

```java
// In any service
@Autowired
private PostMapper postMapper;

Post post = postRepository.findById(id).get();
PostResponse response = postMapper.toResponse(post);
// Returns complete PostResponse with author, category, tags
```

### Still Has Issues:

- **N+1 queries** - Will be fixed in optimization commit
- **No services** - Mappers exist but services not yet created
- **No controllers** - Can't test via API yet

---

## Comparison: Before and After Mappers

### Before (DTO static method)

```java
// In controller or service
Post post = postRepository.findById(id).get();
PostResponse response = PostResponse.fromEntity(post);
```

**Problem:** Can't inject dependencies, hard to test.

### After (Mapper component)

```java
// In service
@Autowired
private PostMapper postMapper;

Post post = postRepository.findById(id).get();
PostResponse response = postMapper.toResponse(post);
```

**Benefit:** DI works, easy to mock, can optimize later.

---

## Architectural Decisions Record

**ADR-034: Use Dedicated Mapper Classes**
- Status: Accepted
- Context: Need to convert entities to DTOs
- Decision: Create @Component mapper classes instead of only static methods
- Consequences: Enables DI, easier testing, more classes to maintain

**ADR-035: Constructor Injection for Mappers**
- Status: Accepted
- Context: PostMapper needs other mappers
- Decision: Use constructor injection over field injection
- Consequences: Explicit dependencies, easier testing, immutable

**ADR-036: Provide Both Instance and Static Methods**
- Status: Accepted
- Context: Some contexts don't have Spring (tests, DTO methods)
- Decision: Provide static methods alongside instance methods
- Consequences: Flexibility, some code duplication

**ADR-037: Ship with Known N+1 Problem**
- Status: Accepted
- Context: PostMapper causes N+1 queries
- Decision: Intentionally ship with this problem, fix in next commit
- Consequences: Students learn to identify N+1, clear optimization path

---

## Commit Stats

- **Files created:** 4
    - UserMapper.java (75 lines)
    - CategoryMapper.java (50 lines)
    - TagMapper.java (70 lines)
    - PostMapper.java (120 lines)
- **Total lines:** ~315
- **Methods defined:** 20+
- **Known issues:** N+1 queries (intentional)

---

## Next Steps

After this commit, we should:
1. **Create SlugService** - Generate URL-friendly slugs
2. **Create PostService** - Use PostMapper, handle business logic
3. **Optimize N+1** - Add JOIN FETCH queries to repository
4. **Create Controllers** - REST endpoints using services

---

## Resources for Students

- N+1 Query Problem: https://vladmihalcea.com/n-plus-1-query-problem/
- MapStruct (Alternative): https://mapstruct.org/
- Spring Dependency Injection: https://www.baeldung.com/constructor-injection-in-spring
- JPA Fetch Strategies: https://www.baeldung.com/hibernate-lazy-eager-loading
- Testing with Mockito: https://www.baeldung.com/mockito-annotations
