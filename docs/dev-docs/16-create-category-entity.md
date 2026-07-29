# Commit 16: Create Category Entity

**Date:** February 12, 2026
**Commit Message:** Create Category entity for post categorization

---

## Development Mindset & Decision Process

### Why Categories Now?

**Current State:**
- User authentication system complete
- JWT authentication working
- Global exception handling in place
- But no content model yet!

**Goal:**
- Create Category entity for organizing blog posts
- Categories are essential for content discovery
- Users browse by category (e.g., "Technology", "Travel")
- SEO-friendly URLs with slugs

**Teaching Point:** Before creating posts, we need categories. A post belongs to categories. Building the supporting entities first prevents circular dependency issues and makes the data model cleaner.

---

## Decision 1: Category Entity Design

### Complete Category Entity

```java
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
```

### Breaking Down Each Design Decision

#### 1. Why Separate Category Entity?

**Alternative 1: Tags as Strings**
```java
// On Post entity
private List<String> tags; // ["tech", "java", "spring"]
```

**Problems:**
- No referential integrity
- Typos create duplicates ("Java" vs "java" vs "JAVA")
- Cannot store category metadata (description, image)
- Hard to rename (must update every post)

**Alternative 2: Category Entity (Our Choice)**
```java
// Category entity
private Long id;
private String name;
private String slug;
private String description;

// Post entity (future)
@ManyToMany
private Set<Category> categories;
```

**Advantages:**
- Single source of truth for category names
- Can add metadata (description, icon, color)
- Easy to rename (update one record)
- Enforces consistency
- Better for SEO

**Teaching Point:** Normalize data that has its own identity or metadata. Categories aren't just strings - they're entities with properties.

#### 2. Name Field

```java
@Column(nullable = false, unique = true, length = 100)
private String name;
```

**nullable = false:**
- Every category must have a name
- "Untitled Category" is bad UX

**unique = true:**
- No duplicate category names
- "Technology" can only exist once
- Database enforces this

**length = 100:**
- Reasonable limit for category names
- "Artificial Intelligence and Machine Learning" = 45 chars
- 100 is generous but prevents abuse

**Teaching Point:** Category names should be human-readable and display-friendly. This is what users see.

#### 3. Slug Field - The URL-Friendly Identifier

```java
@Column(nullable = false, unique = true, length = 120)
private String slug;
```

**What is a slug?**
```
Name: "Artificial Intelligence"
Slug: "artificial-intelligence"

URL: https://blog.com/categories/artificial-intelligence
```

**Why slugs matter:**

1. **SEO-Friendly URLs:**
   ```
   Bad:  /categories/123
   Good: /categories/artificial-intelligence
   ```
   Search engines prefer descriptive URLs.

2. **Human-Readable:**
   Users can understand what they're clicking:
   ```
   /categories/technology vs /categories/7
   ```

3. **Shareable:**
   Slug URLs are meaningful when shared.

4. **Stable:**
   IDs might change (database migration), slugs stay constant.

**Slug Rules:**
- Lowercase: "Technology" → "technology"
- Hyphens for spaces: "Machine Learning" → "machine-learning"
- No special characters: "C++" → "cpp" or "c-plus-plus"
- ASCII only: "Café" → "cafe"

**Why length = 120?**
- Name is 100 chars
- Slug might add hyphens (spaces become hyphens)
- Extra buffer for safety

**Why unique?**
- Slugs are used in URLs
- Two categories can't have same URL
- Database constraint prevents conflicts

**Teaching Point:** Always add slugs to user-facing entities. They're essential for SEO and UX. Generate slugs automatically from names, but allow manual override.

#### 4. Description Field

```java
@Column(length = 500)
private String description;
```

**No `nullable = false`:**
- Description is optional
- Some categories are self-explanatory

**Why description?**
- **SEO:** Meta description for category pages
- **UX:** Help users understand what's in the category
- **Admin:** Notes for content moderators

**Example:**
```
Name: "Technology"
Description: "Articles about software development, programming
languages, frameworks, and tech industry news."
```

**Length = 500:**
- Enough for a good description
- Not a full article (that would be weird)
- Meta descriptions typically 150-160 chars, we allow more

#### 5. Auditing Fields

```java
@CreationTimestamp
@Column(nullable = false, updatable = false)
private LocalDateTime createdAt;

@UpdateTimestamp
@Column(nullable = false)
private LocalDateTime updatedAt;
```

Same pattern as User entity:
- **createdAt:** When category was created (immutable)
- **updatedAt:** When category was last modified (auto-updates)

**Why audit categories?**
- Track when categories were added
- Know last modification time
- Useful for caching ("If-Modified-Since" headers)

---

## Decision 2: Why No Soft Delete for Categories?

### User Entity Has Soft Delete:
```java
private Boolean isDeleted = false;
```

### Category Entity Does Not. Why?

**Users have soft delete because:**
- Users have associated data (posts, comments)
- Legal requirements (GDPR)
- Accidental deletion recovery
- User might return

**Categories don't need soft delete because:**
- Admin-controlled (less likely to be deleted accidentally)
- Can't "delete" a category if posts use it (foreign key)
- No legal requirement to retain
- If you delete it, you meant to

**What happens when deleting a category with posts?**

**Option 1: Prevent deletion**
```java
if (category.getPosts().size() > 0) {
    throw new CannotDeleteException("Category has posts");
}
```

**Option 2: Move posts to "Uncategorized"**
```java
Post.setCategory(uncategorizedCategory);
```

**Option 3: Cascade delete (DON'T DO THIS)**
```java
// Deleting category deletes all posts - BAD!
```

**Teaching Point:** Not every entity needs soft delete. Evaluate based on use case. Over-engineering creates unnecessary complexity.

---

## Decision 3: Flyway Migration (V2__create_categories_table.sql)

### The SQL Migration

```sql
-- V2: Create categories table
CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    slug VARCHAR(120) UNIQUE NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on slug for faster lookups
CREATE INDEX idx_categories_slug ON categories(slug);

-- Create index on name for searching
CREATE INDEX idx_categories_name ON categories(name);
```

### Why These Indexes?

**Index on slug:**
```sql
SELECT * FROM categories WHERE slug = 'technology';
```
- Most common query (URL routing)
- Used on every category page request
- Must be fast

**Index on name:**
```sql
SELECT * FROM categories WHERE name LIKE 'Tech%';
```
- Admin searches by name
- Autocomplete features
- Duplicate checking

**Teaching Point:** Index columns used in WHERE clauses, especially for URL-based lookups. Slug queries happen on every page load - they must be indexed.

---

## Decision 4: No Parent Category (Flat Structure)

### Alternative: Hierarchical Categories

```java
@ManyToOne
private Category parent;

@OneToMany(mappedBy = "parent")
private List<Category> children;
```

**Tree structure:**
```
Technology
├── Programming
│   ├── Java
│   └── Python
└── DevOps
    ├── Docker
    └── Kubernetes
```

### Why We Chose Flat Structure

**Flat:**
- Technology
- Programming
- Java
- Python
- DevOps

**Advantages of flat:**
1. **Simpler queries:** No recursive CTEs needed
2. **Easier UI:** Simple list, no tree navigation
3. **Sufficient:** Most blogs use flat categories
4. **Performance:** No parent lookups

**When to use hierarchy:**
- E-commerce (Electronics > Phones > iPhone)
- Documentation sites
- Complex taxonomies

**Teaching Point:** Start simple. Add hierarchy later if needed. Most blogs never need it. YAGNI (You Ain't Gonna Need It).

---

## Files Created

### 1. src/main/java/com/studylink/blogcms/entity/Category.java

```java
package com.studylink.blogcms.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
```

### 2. src/main/resources/db/migrations/V2__create_categories_table.sql

```sql
-- V2: Create categories table
CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    slug VARCHAR(120) UNIQUE NOT NULL,
    description VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on slug for faster lookups
CREATE INDEX idx_categories_slug ON categories(slug);

-- Create index on name for searching
CREATE INDEX idx_categories_name ON categories(name);
```

---

## What Works Now After This Commit?

### Application Startup Sequence:

1. **Spring Boot starts**
2. **Database connects** (localhost:5432/blog-cms)
3. **Flyway runs:**
    - Checks `flyway_schema_history` table
    - Sees V1 already applied
    - Runs V2__create_categories_table.sql
    - Creates `categories` table with indexes
4. **Hibernate validates:**
    - Checks Category entity matches categories table
    - Validation passes
5. **Application ready** on port 8081

### We Can Now (Theoretically):

- Create Category objects: `Category.builder().name("Technology").slug("technology").build();`
- Save to database (once repository exists)
- Query categories (once repository exists)

### We Still Need:

- **CategoryRepository** - Spring Data JPA interface
- **CategoryService** - Business logic (slug generation)
- **CategoryController** - REST endpoints
- **Post entity** - To use categories
- **Post-Category relationship** - ManyToMany mapping

---

## Future Considerations

### Slug Generation Service

Will need a utility to generate slugs:

```java
@Service
public class SlugService {

    public String generateSlug(String input) {
        return input
            .toLowerCase()
            .trim()
            .replaceAll("[^a-z0-9\\s-]", "")  // Remove special chars
            .replaceAll("\\s+", "-")           // Spaces to hyphens
            .replaceAll("-+", "-")             // Multiple hyphens to single
            .replaceAll("^-|-$", "");          // Trim leading/trailing hyphens
    }

    public String generateUniqueSlug(String input,
            Function<String, Boolean> existsCheck) {
        String baseSlug = generateSlug(input);
        String slug = baseSlug;
        int counter = 1;

        while (existsCheck.apply(slug)) {
            slug = baseSlug + "-" + counter++;
        }

        return slug;
    }
}
```

### Post-Category Relationship (Coming Soon)

```java
// In Post entity
@ManyToMany
@JoinTable(
    name = "post_categories",
    joinColumns = @JoinColumn(name = "post_id"),
    inverseJoinColumns = @JoinColumn(name = "category_id")
)
private Set<Category> categories = new HashSet<>();

// In Category entity
@ManyToMany(mappedBy = "categories")
private Set<Post> posts = new HashSet<>();
```

---

## Architectural Decisions Record

**ADR-016: Use Flat Category Structure**
- Status: Accepted
- Context: Need to organize posts into categories
- Decision: Flat structure (no parent-child hierarchy)
- Consequences: Simpler implementation, sufficient for blog use case

**ADR-017: Add Slug Field for SEO**
- Status: Accepted
- Context: Need URL-friendly identifiers for categories
- Decision: Separate slug field, unique, indexed
- Consequences: Better SEO, human-readable URLs, slight overhead for slug generation

**ADR-018: No Soft Delete for Categories**
- Status: Accepted
- Context: Consider whether categories need soft delete
- Decision: Hard delete only, with foreign key protection
- Consequences: Simpler model, must handle category-post relationships carefully

---

## Commit Stats

- **Files created:** 2
    - Category.java entity (38 lines)
    - V2__create_categories_table.sql (14 lines)
- **Lines of code:** ~52
- **Tables created:** 1 (categories)
- **Indexes created:** 2 (slug, name)

---

## Next Steps

After this commit, we should:
1. **Create CategoryRepository** - Spring Data JPA interface
2. **Create SlugService** - Generate URL-friendly slugs
3. **Create CategoryService** - Business logic
4. **Create CategoryController** - CRUD endpoints
5. **Create Post entity** - Main content entity
6. **Create Post-Category relationship** - ManyToMany mapping

---

## Resources for Students

- URL Slugs Best Practices: https://moz.com/learn/seo/url
- JPA ManyToMany: https://www.baeldung.com/jpa-many-to-many
- Database Indexing Strategy: https://use-the-index-luke.com/
- SEO-Friendly URLs: https://developers.google.com/search/docs/crawling-indexing/url-structure
