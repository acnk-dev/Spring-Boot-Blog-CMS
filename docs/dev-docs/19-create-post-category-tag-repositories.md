# Commit 19: Create Post/Category/Tag Repositories

**Date:** February 12, 2026
**Commit Message:** Create repositories for Post, Category, and Tag

---

## Development Mindset & Decision Process

### Why Repositories Now?

**Current State:**
- All entities created (User, Category, Tag, Post)
- Database tables and relationships defined
- But no way to interact with the database from Java!

**Goal:**
- Create Spring Data JPA repositories
- Define custom query methods
- Enable CRUD operations on all entities
- Add specialized queries for common use cases

**Teaching Point:** Repositories are the data access layer. They abstract away SQL and JDBC, letting you work with Java objects. Spring Data JPA generates the implementation automatically.

---

## Decision 1: Spring Data JPA Repository Pattern

### What is a Repository?

```java
@Repository
public interface PostRepository extends JpaRepository<Post, Long> {
    // Spring Data JPA implements this at runtime!
}
```

**What you get for free:**
- `save(entity)` - Insert or update
- `findById(id)` - Find by primary key
- `findAll()` - Get all entities
- `delete(entity)` - Delete entity
- `count()` - Count all entities
- And many more...

### The Magic of Method Naming

Spring Data JPA parses method names and generates queries:

```java
// Method name → Generated SQL
findBySlug(String slug)
→ SELECT * FROM posts WHERE slug = ?

findByStatusAndIsDeletedFalse(PostStatus status)
→ SELECT * FROM posts WHERE status = ? AND is_deleted = false

findByUserIdAndStatusAndIsDeletedFalse(Long userId, PostStatus status)
→ SELECT * FROM posts WHERE user_id = ? AND status = ? AND is_deleted = false
```

### Naming Convention Keywords

| Keyword | SQL Equivalent | Example |
|---------|----------------|---------|
| `And` | AND | `findByNameAndSlug` |
| `Or` | OR | `findByNameOrSlug` |
| `Is`, `Equals` | = | `findByStatusIs` |
| `Between` | BETWEEN | `findByCreatedAtBetween` |
| `LessThan` | < | `findByViewCountLessThan` |
| `GreaterThan` | > | `findByLikeCountGreaterThan` |
| `Like` | LIKE | `findByTitleLike` |
| `StartingWith` | LIKE 'x%' | `findByNameStartingWith` |
| `EndingWith` | LIKE '%x' | `findBySlugEndingWith` |
| `Containing` | LIKE '%x%' | `findByTitleContaining` |
| `OrderBy` | ORDER BY | `findAllByOrderByNameAsc` |
| `Not` | <> | `findByStatusNot` |
| `In` | IN | `findByNameIn` |
| `True` | = true | `findByIsDeletedTrue` |
| `False` | = false | `findByIsDeletedFalse` |

**Teaching Point:** Learn these keywords - they're the vocabulary of Spring Data JPA. Most queries can be expressed through method naming alone.

---

## Decision 2: PostRepository Design

### Repository Overview

```java
@Repository
public interface PostRepository extends JpaRepository<Post, Long> {
    // 15+ custom methods for various use cases
}
```

### Core Query Methods

#### 1. Find by Status (Homepage/Blog Page)

```java
Page<Post> findByStatusAndIsDeletedFalse(PostStatus status, Pageable pageable);
```

**Use case:** Homepage showing published posts
```java
Pageable pageable = PageRequest.of(0, 10, Sort.by("publishedAt").descending());
Page<Post> publishedPosts = postRepository.findByStatusAndIsDeletedFalse(
    PostStatus.PUBLISHED, pageable);
```

**Why pagination?**
- Blogs can have thousands of posts
- Loading all at once is slow and memory-intensive
- Users only see 10-20 posts per page

#### 2. Find by Author (Profile Page)

```java
Page<Post> findByUserIdAndIsDeletedFalse(Long userId, Pageable pageable);
```

**Use case:** Author profile showing their posts
```java
Page<Post> authorPosts = postRepository.findByUserIdAndIsDeletedFalse(
    author.getId(), pageable);
```

#### 3. Find by Slug (Single Post Page)

```java
Optional<Post> findBySlugAndIsDeletedFalse(String slug);
```

**Use case:** URL routing for post pages
```java
// URL: /posts/getting-started-with-spring-boot
Post post = postRepository.findBySlugAndIsDeletedFalse("getting-started-with-spring-boot")
    .orElseThrow(() -> new PostNotFoundException("Post not found"));
```

**Why Optional?**
- Post might not exist
- Slug might be wrong
- Forces explicit handling of "not found" case

#### 4. Find by Category (Category Page)

```java
Page<Post> findByCategoryIdAndStatusAndIsDeletedFalse(
    Long categoryId, PostStatus status, Pageable pageable);
```

**Use case:** Category page showing posts in that category
```java
// URL: /categories/technology
Page<Post> techPosts = postRepository.findByCategoryIdAndStatusAndIsDeletedFalse(
    techCategory.getId(), PostStatus.PUBLISHED, pageable);
```

#### 5. Find by Tag (Tag Page)

```java
Page<Post> findByTagsIdAndStatusAndIsDeletedFalse(
    Long tagId, PostStatus status, Pageable pageable);
```

**Use case:** Tag page showing posts with that tag
```java
// URL: /tags/java
Page<Post> javaPosts = postRepository.findByTagsIdAndStatusAndIsDeletedFalse(
    javaTag.getId(), PostStatus.PUBLISHED, pageable);
```

**How does `TagsId` work?**
- `Tags` refers to the `tags` field in Post entity
- `Id` refers to the `id` field of Tag entity
- Spring Data JPA navigates the relationship automatically

### Custom @Query Methods

#### Full-Text Search

```java
@Query("SELECT p FROM Post p WHERE p.isDeleted = false " +
       "AND p.status = 'PUBLISHED' " +
       "AND (LOWER(p.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
       "OR LOWER(p.content) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
Page<Post> searchPublishedPosts(@Param("searchTerm") String searchTerm, Pageable pageable);
```

**Why custom query?**
- Complex OR condition
- Case-insensitive search
- Method naming would be verbose: `findByIsDeletedFalseAndStatusAndTitleContainingIgnoreCaseOrContentContainingIgnoreCase`

**Use case:** Search functionality
```java
Page<Post> results = postRepository.searchPublishedPosts("spring boot", pageable);
```

### Atomic Counter Updates

```java
@Modifying
@Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :postId")
void incrementViewCount(@Param("postId") Long postId);

@Modifying
@Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
void incrementLikeCount(@Param("postId") Long postId);

@Modifying
@Query("UPDATE Post p SET p.likeCount = CASE WHEN p.likeCount > 0 THEN p.likeCount - 1 ELSE 0 END WHERE p.id = :postId")
void decrementLikeCount(@Param("postId") Long postId);
```

**Why @Modifying?**
- Tells Spring this query modifies data
- Required for UPDATE/DELETE queries
- Triggers cache invalidation

**Why atomic updates?**
```java
// BAD: Race condition!
Post post = postRepository.findById(1L).get();
post.setViewCount(post.getViewCount() + 1);  // Thread A reads 100
postRepository.save(post);                    // Thread B also reads 100
// Both write 101, but should be 102!

// GOOD: Atomic update
postRepository.incrementViewCount(1L);  // Database handles concurrency
```

**Decrement safety:**
```java
CASE WHEN p.likeCount > 0 THEN p.likeCount - 1 ELSE 0 END
```
- Prevents negative counts
- Handles edge cases (unlike someone who never liked)

---

## Decision 3: CategoryRepository Design

### Repository Overview

```java
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {
    // Simpler than PostRepository - categories are admin-managed
}
```

### Key Methods

#### Find by Slug

```java
Optional<Category> findBySlug(String slug);
```

**Use case:** URL routing
```java
// URL: /categories/technology
Category category = categoryRepository.findBySlug("technology")
    .orElseThrow(() -> new CategoryNotFoundException("Category not found"));
```

#### Existence Checks

```java
boolean existsByName(String name);
boolean existsBySlug(String slug);
```

**Use case:** Validation before creating
```java
if (categoryRepository.existsByName(request.getName())) {
    throw new DuplicateCategoryException("Category already exists");
}
```

**Why `exists` instead of `find`?**
- More efficient: `SELECT COUNT(*) > 0` vs `SELECT *`
- Don't need the actual entity, just need to know if it exists

#### Sorted Listing

```java
List<Category> findAllByOrderByNameAsc();
```

**Use case:** Category dropdown, navigation menu
```java
List<Category> categories = categoryRepository.findAllByOrderByNameAsc();
// Returns: [Business, Lifestyle, Technology, Travel]
```

#### Autocomplete

```java
@Query("SELECT c FROM Category c WHERE LOWER(c.name) LIKE LOWER(CONCAT(:prefix, '%')) ORDER BY c.name")
List<Category> findByNameStartingWith(@Param("prefix") String prefix);
```

**Use case:** Admin typing category name
```java
// User types "Tech"
List<Category> suggestions = categoryRepository.findByNameStartingWith("Tech");
// Returns: [Technology, Technical Writing]
```

---

## Decision 4: TagRepository Design

### Repository Overview

```java
@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {
    // Tag-specific operations including bulk lookups
}
```

### Key Methods

#### Bulk Lookup by Name

```java
List<Tag> findByNameIn(List<String> names);
Set<Tag> findByNameIn(Set<String> names);
```

**Use case:** Processing tag input from forms
```java
// User enters: "java, spring-boot, tutorial"
List<String> tagNames = Arrays.asList("java", "spring-boot", "tutorial");
List<Tag> existingTags = tagRepository.findByNameIn(tagNames);

// Find which tags need to be created
Set<String> existingNames = existingTags.stream()
    .map(Tag::getName)
    .collect(Collectors.toSet());

List<String> newTagNames = tagNames.stream()
    .filter(name -> !existingNames.contains(name))
    .toList();
```

**Why both List and Set versions?**
- List: Preserves order, allows duplicates
- Set: When you need unique results

#### Popular Tags (Tag Cloud)

```java
@Query("SELECT t FROM Tag t LEFT JOIN t.posts p " +
       "WHERE p.isDeleted = false AND p.status = 'PUBLISHED' " +
       "GROUP BY t ORDER BY COUNT(p) DESC")
List<Tag> findPopularTags(@Param("limit") int limit);
```

**Use case:** Tag cloud, sidebar widget
```java
List<Tag> popularTags = tagRepository.findPopularTags(20);
// Returns tags sorted by post count: [java (50), spring (40), tutorial (30), ...]
```

**How it works:**
1. LEFT JOIN tags with posts
2. Filter: only published, non-deleted posts
3. GROUP BY tag
4. ORDER BY count descending

#### Tags by Author

```java
@Query("SELECT DISTINCT t FROM Tag t JOIN t.posts p " +
       "WHERE p.user.id = :userId AND p.isDeleted = false")
List<Tag> findTagsByAuthor(@Param("userId") Long userId);
```

**Use case:** Author profile showing their expertise
```java
List<Tag> authorTags = tagRepository.findTagsByAuthor(author.getId());
// Returns: [java, spring-boot, microservices] - topics this author writes about
```

---

## Decision 5: Bidirectional Relationship Update

### Why Update Tag Entity?

The `findPopularTags` and `findTagsByAuthor` queries join Tag with Post:
```java
LEFT JOIN t.posts p
```

For this to work, Tag must have a `posts` field.

### The Update

```java
// In Tag.java
@ManyToMany(mappedBy = "tags")
@Builder.Default
private Set<Post> posts = new HashSet<>();
```

### Understanding `mappedBy`

```java
// In Post.java (owning side)
@ManyToMany
@JoinTable(
    name = "post_tags",
    joinColumns = @JoinColumn(name = "post_id"),
    inverseJoinColumns = @JoinColumn(name = "tag_id")
)
private Set<Tag> tags = new HashSet<>();

// In Tag.java (inverse side)
@ManyToMany(mappedBy = "tags")  // "tags" refers to Post.tags field
private Set<Post> posts = new HashSet<>();
```

**Owning side (Post):**
- Has @JoinTable annotation
- Controls the relationship in database
- Adding/removing tags happens through Post.tags

**Inverse side (Tag):**
- Has `mappedBy` attribute
- Read-only view of the relationship
- Useful for queries, not for modifications

### Why Post is the Owning Side?

**Semantic meaning:**
- "Add tag to post" makes more sense than "Add post to tag"
- When editing a post, you select its tags
- When creating a post, you assign tags

**Practical consideration:**
- Posts are edited more often than tags
- Post editor needs to manage tags
- Tag pages just need to list posts (read-only)

**Teaching Point:** In ManyToMany, one side must own the relationship. Choose the side that's more likely to modify the relationship.

---

## Decision 6: Query Return Types

### When to Use What

| Return Type | Use Case | Example |
|-------------|----------|---------|
| `Optional<T>` | Single entity, might not exist | `findBySlug` |
| `T` | Single entity, must exist | `getById` (throws if not found) |
| `List<T>` | Multiple entities, all at once | `findByNameIn` |
| `Page<T>` | Multiple entities, paginated | `findByStatus` |
| `boolean` | Existence check | `existsByName` |
| `long` | Count | `countByStatus` |
| `void` | Modifying queries | `incrementViewCount` |

### Page vs List

**List - Load everything:**
```java
List<Post> allPosts = postRepository.findAll();
// Loads ALL posts into memory - dangerous for large datasets!
```

**Page - Load incrementally:**
```java
Pageable pageable = PageRequest.of(0, 10);  // Page 0, 10 items
Page<Post> page = postRepository.findByStatusAndIsDeletedFalse(status, pageable);

page.getContent();       // List<Post> - the actual posts
page.getTotalElements(); // Total count across all pages
page.getTotalPages();    // Number of pages
page.getNumber();        // Current page number
page.hasNext();          // Are there more pages?
```

**Teaching Point:** Always use `Page` for user-facing lists. `List` is fine for admin operations with known small datasets.

---

## Files Created/Modified

### 1. src/main/java/com/studylink/blogcms/repository/PostRepository.java

```java
@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    // Status-based queries
    Page<Post> findByStatusAndIsDeletedFalse(PostStatus status, Pageable pageable);
    Page<Post> findByIsDeletedFalse(Pageable pageable);

    // Author queries
    Page<Post> findByUserIdAndIsDeletedFalse(Long userId, Pageable pageable);
    Page<Post> findByUserIdAndStatusAndIsDeletedFalse(Long userId, PostStatus status, Pageable pageable);
    long countByUserIdAndIsDeletedFalse(Long userId);

    // Slug queries
    Optional<Post> findBySlugAndIsDeletedFalse(String slug);
    Optional<Post> findBySlug(String slug);
    boolean existsBySlug(String slug);

    // Category queries
    Page<Post> findByCategoryIdAndStatusAndIsDeletedFalse(Long categoryId, PostStatus status, Pageable pageable);
    long countByCategoryId(Long categoryId);
    long countByCategoryIdAndStatusAndIsDeletedFalse(Long categoryId, PostStatus status);

    // Tag queries
    List<Post> findByTagsIdAndStatusAndIsDeletedFalse(Long tagId, PostStatus status);
    Page<Post> findByTagsIdAndStatusAndIsDeletedFalse(Long tagId, PostStatus status, Pageable pageable);

    // Search
    @Query("SELECT p FROM Post p WHERE p.isDeleted = false AND p.status = 'PUBLISHED' " +
           "AND (LOWER(p.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "OR LOWER(p.content) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
    Page<Post> searchPublishedPosts(@Param("searchTerm") String searchTerm, Pageable pageable);

    // Counter updates
    @Modifying
    @Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :postId")
    void incrementViewCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = p.likeCount + 1 WHERE p.id = :postId")
    void incrementLikeCount(@Param("postId") Long postId);

    @Modifying
    @Query("UPDATE Post p SET p.likeCount = CASE WHEN p.likeCount > 0 THEN p.likeCount - 1 ELSE 0 END WHERE p.id = :postId")
    void decrementLikeCount(@Param("postId") Long postId);
}
```

### 2. src/main/java/com/studylink/blogcms/repository/CategoryRepository.java

```java
@Repository
public interface CategoryRepository extends JpaRepository<Category, Long> {

    Optional<Category> findBySlug(String slug);
    Optional<Category> findByName(String name);

    boolean existsByName(String name);
    boolean existsBySlug(String slug);

    List<Category> findAllByOrderByNameAsc();

    @Query("SELECT c FROM Category c WHERE LOWER(c.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Category> searchByName(@Param("searchTerm") String searchTerm);

    @Query("SELECT c FROM Category c WHERE LOWER(c.name) LIKE LOWER(CONCAT(:prefix, '%')) ORDER BY c.name")
    List<Category> findByNameStartingWith(@Param("prefix") String prefix);
}
```

### 3. src/main/java/com/studylink/blogcms/repository/TagRepository.java

```java
@Repository
public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findBySlug(String slug);
    Optional<Tag> findByName(String name);

    List<Tag> findByNameIn(List<String> names);
    Set<Tag> findByNameIn(Set<String> names);

    boolean existsByName(String name);
    boolean existsBySlug(String slug);

    List<Tag> findAllByOrderByNameAsc();

    @Query("SELECT t FROM Tag t WHERE LOWER(t.name) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Tag> searchByName(@Param("searchTerm") String searchTerm);

    @Query("SELECT t FROM Tag t WHERE LOWER(t.name) LIKE LOWER(CONCAT(:prefix, '%')) ORDER BY t.name")
    List<Tag> findByNameStartingWith(@Param("prefix") String prefix);

    @Query("SELECT t FROM Tag t LEFT JOIN t.posts p WHERE p.isDeleted = false AND p.status = 'PUBLISHED' GROUP BY t ORDER BY COUNT(p) DESC")
    List<Tag> findPopularTags(@Param("limit") int limit);

    @Query("SELECT DISTINCT t FROM Tag t JOIN t.posts p WHERE p.user.id = :userId AND p.isDeleted = false")
    List<Tag> findTagsByAuthor(@Param("userId") Long userId);
}
```

### 4. src/main/java/com/studylink/blogcms/entity/Tag.java (Modified)

```java
// Added bidirectional relationship
@ManyToMany(mappedBy = "tags")
@Builder.Default
private Set<Post> posts = new HashSet<>();
```

---

## Common Patterns Across Repositories

### 1. Soft Delete Filtering

Every repository that deals with user-facing content includes `IsDeletedFalse`:

```java
findBySlugAndIsDeletedFalse()      // Don't show deleted posts
findByUserIdAndIsDeletedFalse()    // Author's visible posts only
findByStatusAndIsDeletedFalse()    // Published, non-deleted posts
```

**Convention:** Suffix methods with `AndIsDeletedFalse` for public-facing queries.

### 2. Slug-Based Lookups

Every entity with URLs has slug lookup:

```java
// PostRepository
Optional<Post> findBySlugAndIsDeletedFalse(String slug);

// CategoryRepository
Optional<Category> findBySlug(String slug);

// TagRepository
Optional<Tag> findBySlug(String slug);
```

### 3. Existence Checks for Validation

```java
boolean existsByName(String name);
boolean existsBySlug(String slug);
```

**Use before creating:** Prevent duplicates at service layer.

### 4. Alphabetical Listing

```java
List<Category> findAllByOrderByNameAsc();
List<Tag> findAllByOrderByNameAsc();
```

**For dropdowns and navigation:** Consistent, predictable order.

---

## What Works Now After This Commit?

### Database Operations Available:

```java
// Posts
postRepository.save(post);
postRepository.findBySlugAndIsDeletedFalse("my-post");
postRepository.findByStatusAndIsDeletedFalse(PostStatus.PUBLISHED, pageable);
postRepository.searchPublishedPosts("spring boot", pageable);
postRepository.incrementViewCount(1L);

// Categories
categoryRepository.findBySlug("technology");
categoryRepository.existsByName("Technology");
categoryRepository.findAllByOrderByNameAsc();

// Tags
tagRepository.findByNameIn(Arrays.asList("java", "spring"));
tagRepository.findPopularTags(20);
tagRepository.findByNameStartingWith("spr");
```

### Still Needed:

- **Service layer** - Business logic, transaction management
- **DTOs** - Request/Response objects
- **Controllers** - REST endpoints
- **Exception handling** - Custom exceptions for not found, duplicates

---

## Performance Considerations

### N+1 Query Problem

**Problem:**
```java
List<Post> posts = postRepository.findByStatusAndIsDeletedFalse(status, pageable);
for (Post post : posts) {
    System.out.println(post.getUser().getName());  // N additional queries!
}
```

**Solution - Use JOIN FETCH in service layer:**
```java
@Query("SELECT p FROM Post p JOIN FETCH p.user WHERE p.status = :status AND p.isDeleted = false")
Page<Post> findByStatusWithUser(@Param("status") PostStatus status, Pageable pageable);
```

### Index Usage

Our indexes align with repository methods:

| Method | Index Used |
|--------|------------|
| `findBySlugAndIsDeletedFalse` | `idx_posts_slug` |
| `findByStatusAndIsDeletedFalse` | `idx_posts_published_active` (composite) |
| `findByUserIdAndIsDeletedFalse` | `idx_posts_user_id` |
| `findByCategoryIdAndStatusAndIsDeletedFalse` | `idx_posts_category_id` |

---

## Architectural Decisions Record

**ADR-026: Use Spring Data JPA Method Naming**
- Status: Accepted
- Context: Need to define repository queries
- Decision: Use method naming conventions where possible, @Query for complex cases
- Consequences: Less code, automatic implementation, readable method names

**ADR-027: Always Filter Soft Deletes in Public Queries**
- Status: Accepted
- Context: Posts have soft delete, queries must respect this
- Decision: Add `AndIsDeletedFalse` to all user-facing query methods
- Consequences: Consistent behavior, deleted content never shown accidentally

**ADR-028: Use Page for User-Facing Lists**
- Status: Accepted
- Context: Need to handle large datasets efficiently
- Decision: Return `Page<T>` for any query that could return many results
- Consequences: Better performance, built-in pagination metadata

**ADR-029: Atomic Counter Updates**
- Status: Accepted
- Context: View/like counts need concurrent update safety
- Decision: Use @Modifying @Query for counter increments
- Consequences: Thread-safe, no lost updates

---

## Commit Stats

- **Files created:** 3
    - PostRepository.java (165 lines)
    - CategoryRepository.java (65 lines)
    - TagRepository.java (90 lines)
- **Files modified:** 1
    - Tag.java (added bidirectional mapping)
- **Total methods defined:** 35+
- **Custom @Query methods:** 8

---

## Next Steps

After this commit, we should:
1. **Create SlugService** - Generate unique slugs for posts/categories/tags
2. **Create PostService** - Business logic for posts
3. **Create CategoryService** - Business logic for categories
4. **Create TagService** - Business logic for tags
5. **Create DTOs** - Request/Response objects
6. **Create Controllers** - REST endpoints

---

## Resources for Students

- Spring Data JPA Reference: https://docs.spring.io/spring-data/jpa/docs/current/reference/html/
- Query Methods: https://www.baeldung.com/spring-data-derived-queries
- @Query Annotation: https://www.baeldung.com/spring-data-jpa-query
- Pagination: https://www.baeldung.com/spring-data-jpa-pagination-sorting
- N+1 Problem: https://vladmihalcea.com/n-plus-1-query-problem/
