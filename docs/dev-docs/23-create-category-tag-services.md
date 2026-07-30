# Commit 23: Create CategoryService and TagService

**Date:** February 12, 2026
**Commit Message:** Create services for Category and Tag management

---

## Development Mindset & Decision Process

### Why Category and Tag Services Now?

**Current State:**
- PostService created with full CRUD
- Posts reference categories and tags
- But no way to manage categories/tags themselves!

**Goal:**
- Create CategoryService for category management
- Create TagService for tag management
- Enable admin operations for content taxonomy
- Support autocomplete and popular tags features

**Teaching Point:** Categories and tags are supporting entities. While simpler than posts, they still need proper service layers with validation, slug generation, and business rules.

---

## Decision 1: Category vs Tag Deletion Strategy

### The Key Difference

| Entity | Delete Strategy | Reason |
|--------|-----------------|--------|
| Category | **Prevent** if posts exist | Posts need a category for organization |
| Tag | **Allow** always (CASCADE) | Tags are optional, posts can exist without them |

### Category Deletion - Protected

```java
@Override
@Transactional
public void deleteCategory(Long id) {
    Category category = categoryRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Category", "id", id));

    // Check if any posts use this category
    long postCount = postRepository.countByCategoryId(id);
    if (postCount > 0) {
        throw new BadRequestException(
                "Cannot delete category '" + category.getName() +
                "'. It is used by " + postCount + " post(s). " +
                "Please reassign or delete those posts first.");
    }

    categoryRepository.delete(category);
}
```

**Why protect categories?**
- Categories define site structure
- Posts require categories for navigation
- Deleting breaks user expectations
- Admin should consciously reassign posts

### Tag Deletion - Allowed (CASCADE)

```java
@Override
@Transactional
public void deleteTag(Long id) {
    Tag tag = tagRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Tag", "id", id));

    // Tags can be deleted even if posts use them
    // The post_tags junction table has ON DELETE CASCADE
    // Posts will simply lose this tag
    tagRepository.delete(tag);
}
```

**Why allow tag deletion?**
- Tags are supplementary, not structural
- Posts work fine without tags
- Junction table handles cleanup automatically
- Easier tag management (merge by delete + recreate)

**Database CASCADE:**
```sql
-- From V5 migration
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    ...
);
```

---

## Decision 2: Tag Name Normalization

### Why Normalize?

```java
// Without normalization
createTag("Java")      // Creates "Java"
createTag("java")      // Creates "java" - DUPLICATE!
createTag("JAVA")      // Creates "JAVA" - ANOTHER DUPLICATE!

// With normalization
createTag("Java")      // Creates "java"
createTag("java")      // Error: already exists
createTag("JAVA")      // Error: already exists
```

### Implementation

```java
private String normalizeTagName(String name) {
    if (name == null) {
        return "";
    }
    return name.trim().toLowerCase(Locale.ENGLISH);
}

@Override
@Transactional
public TagResponse createTag(CreateTagRequest request) {
    String normalizedName = normalizeTagName(request.getName());

    if (tagRepository.existsByName(normalizedName)) {
        throw new BadRequestException("Tag with name '" + normalizedName + "' already exists");
    }

    // ... create with normalized name
}
```

### Why Lowercase?

| Aspect | Mixed Case | Lowercase |
|--------|------------|-----------|
| **Consistency** | "Java", "java", "JAVA" all different | Always "java" |
| **URLs** | `/tags/Java` vs `/tags/java` | Always `/tags/java` |
| **Search** | Case-sensitive issues | Simple matching |
| **UX** | Confusing duplicates | Clean tag cloud |

**Teaching Point:** Tags should be case-insensitive. Normalize at creation time, not query time.

---

## Decision 3: getOrCreateTags - Bulk Tag Operation

### The Use Case

When saving a post, user enters tag names as text:
```
"java, spring-boot, tutorial"
```

Need to:
1. Find existing tags
2. Create missing tags
3. Return all tag entities

### Implementation

```java
@Override
@Transactional
public List<TagResponse> getOrCreateTags(Set<String> tagNames) {
    if (tagNames == null || tagNames.isEmpty()) {
        return Collections.emptyList();
    }

    // Normalize all tag names
    Set<String> normalizedNames = tagNames.stream()
            .map(this::normalizeTagName)
            .filter(name -> !name.isBlank())
            .collect(Collectors.toSet());

    // Find existing tags
    Set<Tag> existingTags = tagRepository.findByNameIn(normalizedNames);
    Set<String> existingNames = existingTags.stream()
            .map(Tag::getName)
            .collect(Collectors.toSet());

    // Create missing tags
    List<Tag> newTags = normalizedNames.stream()
            .filter(name -> !existingNames.contains(name))
            .map(name -> {
                String slug = generateSlug(name);
                slug = ensureUniqueSlug(slug, null);
                return Tag.builder()
                        .name(name)
                        .slug(slug)
                        .build();
            })
            .collect(Collectors.toList());

    // Save new tags
    List<Tag> savedNewTags = tagRepository.saveAll(newTags);

    // Combine existing and new tags
    List<Tag> allTags = new ArrayList<>(existingTags);
    allTags.addAll(savedNewTags);

    return allTags.stream()
            .map(tagMapper::toResponse)
            .collect(Collectors.toList());
}
```

### Example Flow

```
Input: ["java", "Spring Boot", "new-tag"]

Step 1: Normalize
  → ["java", "spring boot", "new-tag"]

Step 2: Find existing
  → Found: [Tag(id=1, name="java")]
  → Existing names: ["java"]

Step 3: Create missing
  → Create: Tag(name="spring boot", slug="spring-boot")
  → Create: Tag(name="new-tag", slug="new-tag")

Step 4: Return all
  → [Tag(1, "java"), Tag(2, "spring boot"), Tag(3, "new-tag")]
```

### Why This Pattern?

**Alternative: Create individually**
```java
for (String name : tagNames) {
    if (!tagRepository.existsByName(name)) {
        createTag(name);  // N queries to check + N queries to insert
    }
}
```

**Our approach: Batch operations**
```java
Set<Tag> existing = tagRepository.findByNameIn(names);  // 1 query
List<Tag> created = tagRepository.saveAll(newTags);     // 1 query
```

**Teaching Point:** Batch database operations when possible. 2 queries beats N queries.

---

## Decision 4: Slug Generation (Duplicated from SlugUtil)

### Why Duplicate Slug Logic?

```java
// In CategoryServiceImpl and TagServiceImpl
private String generateSlug(String name) {
    String normalized = Normalizer.normalize(name, Normalizer.Form.NFD);
    String slug = WHITESPACE.matcher(normalized).replaceAll("-");
    slug = NON_LATIN.matcher(slug).replaceAll("");
    // ...
}
```

**Could use SlugUtil:**
```java
String slug = SlugUtil.generateSlug(name);
```

**But SlugUtil.generateUniqueSlug requires PostRepository:**
```java
public static String generateUniqueSlug(String title, PostRepository repository)
```

### Options Considered

| Option | Pros | Cons |
|--------|------|------|
| **Duplicate logic** | Simple, no dependencies | Code duplication |
| **Generic SlugUtil** | Single source of truth | Complex generics |
| **SlugService** | Proper DI, testable | Another service to inject |

**We chose duplication because:**
- Slug logic is simple (10 lines)
- Each service has its own repository
- Avoids complex generic abstractions
- Can refactor later if needed

**Teaching Point:** Don't over-engineer. Sometimes duplication is simpler than abstraction. Refactor when duplication becomes painful.

---

## Decision 5: Autocomplete Support

### Tag Prefix Search

```java
@Override
public List<TagResponse> findTagsByPrefix(String prefix) {
    List<Tag> tags = tagRepository.findByNameStartingWith(prefix);

    return tags.stream()
            .map(tagMapper::toResponse)
            .collect(Collectors.toList());
}
```

### Use Case: Tag Input Field

```
User types: "spr"
           ↓
API call: GET /api/tags/autocomplete?prefix=spr
           ↓
Returns: ["spring", "spring-boot", "spring-security", "sprite"]
           ↓
UI shows dropdown with suggestions
```

### Category Search

```java
@Override
public List<CategoryResponse> searchCategories(String searchTerm) {
    List<Category> categories = categoryRepository.searchByName(searchTerm);

    return categories.stream()
            .map(categoryMapper::toResponse)
            .collect(Collectors.toList());
}
```

**Difference:**
- Tags: Prefix search (`LIKE 'spr%'`) - faster, for autocomplete
- Categories: Contains search (`LIKE '%tech%'`) - broader, for admin search

---

## Files Created

### 1. CategoryService.java (Interface)

```java
public interface CategoryService {

    CategoryResponse createCategory(CreateCategoryRequest request);

    CategoryResponse updateCategory(Long id, CreateCategoryRequest request);

    void deleteCategory(Long id);

    CategoryResponse getCategoryById(Long id);

    CategoryResponse getCategoryBySlug(String slug);

    List<CategoryResponse> getAllCategories();

    List<CategoryResponse> searchCategories(String searchTerm);

    boolean existsByName(String name);
}
```

### 2. CategoryServiceImpl.java

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryServiceImpl implements CategoryService {

    private final CategoryRepository categoryRepository;
    private final PostRepository postRepository;
    private final CategoryMapper categoryMapper;

    @Override
    @Transactional
    public CategoryResponse createCategory(CreateCategoryRequest request) {
        // Check if name already exists
        if (categoryRepository.existsByName(request.getName())) {
            throw new BadRequestException("Category already exists");
        }

        String slug = generateSlug(request.getName());
        slug = ensureUniqueSlug(slug, null);

        Category category = Category.builder()
                .name(request.getName())
                .slug(slug)
                .description(request.getDescription())
                .build();

        return categoryMapper.toResponse(categoryRepository.save(category));
    }

    @Override
    @Transactional
    public void deleteCategory(Long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category", "id", id));

        long postCount = postRepository.countByCategoryId(id);
        if (postCount > 0) {
            throw new BadRequestException("Cannot delete category with posts");
        }

        categoryRepository.delete(category);
    }

    // ... other methods
}
```

### 3. TagService.java (Interface)

```java
public interface TagService {

    TagResponse createTag(CreateTagRequest request);

    List<TagResponse> getAllTags();

    TagResponse getTagById(Long id);

    TagResponse getTagBySlug(String slug);

    void deleteTag(Long id);

    List<TagResponse> searchTags(String searchTerm);

    List<TagResponse> findTagsByPrefix(String prefix);

    List<TagResponse> getOrCreateTags(Set<String> tagNames);

    List<TagResponse> getPopularTags(int limit);

    boolean existsByName(String name);
}
```

### 4. TagServiceImpl.java

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagServiceImpl implements TagService {

    private final TagRepository tagRepository;
    private final TagMapper tagMapper;

    @Override
    @Transactional
    public TagResponse createTag(CreateTagRequest request) {
        String normalizedName = normalizeTagName(request.getName());

        if (tagRepository.existsByName(normalizedName)) {
            throw new BadRequestException("Tag already exists");
        }

        String slug = generateSlug(normalizedName);
        slug = ensureUniqueSlug(slug, null);

        Tag tag = Tag.builder()
                .name(normalizedName)
                .slug(slug)
                .build();

        return tagMapper.toResponse(tagRepository.save(tag));
    }

    @Override
    @Transactional
    public List<TagResponse> getOrCreateTags(Set<String> tagNames) {
        // Normalize names
        Set<String> normalizedNames = tagNames.stream()
                .map(this::normalizeTagName)
                .filter(name -> !name.isBlank())
                .collect(Collectors.toSet());

        // Find existing
        Set<Tag> existingTags = tagRepository.findByNameIn(normalizedNames);
        Set<String> existingNames = existingTags.stream()
                .map(Tag::getName)
                .collect(Collectors.toSet());

        // Create missing
        List<Tag> newTags = normalizedNames.stream()
                .filter(name -> !existingNames.contains(name))
                .map(name -> Tag.builder()
                        .name(name)
                        .slug(generateSlug(name))
                        .build())
                .collect(Collectors.toList());

        List<Tag> savedNewTags = tagRepository.saveAll(newTags);

        // Combine and return
        List<Tag> allTags = new ArrayList<>(existingTags);
        allTags.addAll(savedNewTags);

        return allTags.stream()
                .map(tagMapper::toResponse)
                .collect(Collectors.toList());
    }

    private String normalizeTagName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ENGLISH);
    }

    // ... other methods
}
```

---

## Service Method Comparison

### CategoryService Methods

| Method | Description | Transactional |
|--------|-------------|---------------|
| `createCategory` | Create with auto-slug | Write |
| `updateCategory` | Update name/description, regen slug | Write |
| `deleteCategory` | Delete if no posts use it | Write |
| `getCategoryById` | Get by ID | Read |
| `getCategoryBySlug` | Get by slug (URL routing) | Read |
| `getAllCategories` | List all, ordered by name | Read |
| `searchCategories` | Search by name (contains) | Read |
| `existsByName` | Check existence | Read |

### TagService Methods

| Method | Description | Transactional |
|--------|-------------|---------------|
| `createTag` | Create with normalized name, auto-slug | Write |
| `deleteTag` | Delete (CASCADE removes from posts) | Write |
| `getTagById` | Get by ID | Read |
| `getTagBySlug` | Get by slug (URL routing) | Read |
| `getAllTags` | List all, ordered by name | Read |
| `searchTags` | Search by name (contains) | Read |
| `findTagsByPrefix` | Autocomplete (starts with) | Read |
| `getOrCreateTags` | Bulk get/create by names | Write |
| `getPopularTags` | Top tags by post count | Read |
| `existsByName` | Check existence | Read |

---

## Business Rules Summary

### Category Rules

1. **Unique name** - No duplicate category names
2. **Auto-slug** - Generated from name, must be unique
3. **Protected delete** - Cannot delete if posts use it
4. **Update regenerates slug** - If name changes

### Tag Rules

1. **Normalized name** - Lowercase, trimmed
2. **Unique normalized name** - "Java" and "java" are the same
3. **Auto-slug** - Generated from normalized name
4. **Allowed delete** - CASCADE removes from posts
5. **Bulk create** - getOrCreateTags for efficiency

---

## Testing Examples

### Unit Test: Category Delete Protection

```java
@Test
void deleteCategory_shouldThrow_whenPostsExist() {
    // Given
    Long categoryId = 1L;
    Category category = Category.builder()
            .id(categoryId)
            .name("Technology")
            .build();

    when(categoryRepository.findById(categoryId))
            .thenReturn(Optional.of(category));
    when(postRepository.countByCategoryId(categoryId))
            .thenReturn(5L);  // 5 posts use this category

    // When & Then
    BadRequestException exception = assertThrows(
            BadRequestException.class,
            () -> categoryService.deleteCategory(categoryId)
    );

    assertTrue(exception.getMessage().contains("5 post(s)"));
    verify(categoryRepository, never()).delete(any());
}
```

### Unit Test: Tag Normalization

```java
@Test
void createTag_shouldNormalizeName() {
    // Given
    CreateTagRequest request = new CreateTagRequest();
    request.setName("  JAVA  ");  // Uppercase with spaces

    when(tagRepository.existsByName("java")).thenReturn(false);
    when(tagRepository.existsBySlug("java")).thenReturn(false);
    when(tagRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // When
    tagService.createTag(request);

    // Then
    verify(tagRepository).save(argThat(tag ->
            tag.getName().equals("java") &&
            tag.getSlug().equals("java")
    ));
}
```

### Unit Test: getOrCreateTags

```java
@Test
void getOrCreateTags_shouldCreateOnlyMissing() {
    // Given
    Set<String> tagNames = Set.of("java", "spring", "new-tag");

    Tag existingJava = Tag.builder().id(1L).name("java").build();
    Tag existingSpring = Tag.builder().id(2L).name("spring").build();

    when(tagRepository.findByNameIn(anySet()))
            .thenReturn(Set.of(existingJava, existingSpring));
    when(tagRepository.existsBySlug("new-tag")).thenReturn(false);
    when(tagRepository.saveAll(anyList())).thenAnswer(inv -> {
        List<Tag> tags = inv.getArgument(0);
        tags.forEach(t -> t.setId(3L));
        return tags;
    });

    // When
    List<TagResponse> result = tagService.getOrCreateTags(tagNames);

    // Then
    assertEquals(3, result.size());
    verify(tagRepository).saveAll(argThat(list ->
            list.size() == 1 &&  // Only "new-tag" created
            list.get(0).getName().equals("new-tag")
    ));
}
```

---

## What Works Now After This Commit?

### Complete Service Layer:

```
┌─────────────────────────────────────────────────────────┐
│                    Service Layer                         │
│  ┌─────────────┐  ┌───────────────┐  ┌──────────────┐  │
│  │ PostService │  │CategoryService│  │  TagService  │  │
│  │  (12 methods) │  │  (8 methods)  │  │ (10 methods) │  │
│  └─────────────┘  └───────────────┘  └──────────────┘  │
└─────────────────────────────────────────────────────────┘
         │                  │                  │
         ▼                  ▼                  ▼
┌─────────────────────────────────────────────────────────┐
│                   Repository Layer                       │
│  PostRepository  CategoryRepository  TagRepository       │
└─────────────────────────────────────────────────────────┘
```

### Can Now:

```java
// Category management
categoryService.createCategory(request);
categoryService.getAllCategories();
categoryService.deleteCategory(id);  // Fails if posts exist

// Tag management
tagService.createTag(request);
tagService.getOrCreateTags(Set.of("java", "spring"));
tagService.getPopularTags(20);
tagService.findTagsByPrefix("spr");  // Autocomplete
```

### Still Needed:

- **Controllers** - REST endpoints for all services
- **N+1 optimization** - For PostService
- **Admin authorization** - Category/tag management should be admin-only

---

## Architectural Decisions Record

**ADR-042: Protected Delete for Categories**
- Status: Accepted
- Context: Should categories be deletable if posts use them?
- Decision: Prevent deletion, require reassignment first
- Consequences: Data integrity preserved, admin must handle posts

**ADR-043: CASCADE Delete for Tags**
- Status: Accepted
- Context: Should tags be deletable if posts use them?
- Decision: Allow deletion, CASCADE removes from junction table
- Consequences: Easy tag cleanup, posts lose tags silently

**ADR-044: Normalize Tag Names to Lowercase**
- Status: Accepted
- Context: Should "Java" and "java" be the same tag?
- Decision: Normalize to lowercase at creation time
- Consequences: Case-insensitive tags, cleaner tag cloud

**ADR-045: Duplicate Slug Logic in Services**
- Status: Accepted (pragmatic)
- Context: Should slug generation be centralized?
- Decision: Duplicate simple logic in each service
- Consequences: Some duplication, but simpler than generic abstraction

---

## Commit Stats

- **Files created:** 4
    - CategoryService.java (60 lines)
    - CategoryServiceImpl.java (140 lines)
    - TagService.java (75 lines)
    - TagServiceImpl.java (165 lines)
- **Total lines:** ~440
- **Methods defined:** 18 service methods
- **Business rules enforced:** 8

---

## Next Steps

After this commit, we should:
1. **Create Controllers** - PostController, CategoryController, TagController
2. **Optimize N+1** - JOIN FETCH for post queries
3. **Add admin authorization** - Protect category/tag management
4. **Create Postman collection** - Test all endpoints

---

## Resources for Students

- Service Layer Pattern: https://martinfowler.com/eaaCatalog/serviceLayer.html
- CASCADE Delete: https://www.postgresql.org/docs/current/ddl-constraints.html#DDL-CONSTRAINTS-FK
- Soft Delete vs Hard Delete: https://www.baeldung.com/spring-jpa-soft-delete
- Spring @Transactional: https://www.baeldung.com/transaction-configuration-with-jpa-and-spring
- Batch Operations: https://www.baeldung.com/spring-data-jpa-batch-inserts
