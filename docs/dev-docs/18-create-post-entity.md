# Commit 18: Create Post Entity with Relationships

**Date:** February 12, 2026
**Commit Message:** Create Post entity with relationships

---

## Development Mindset & Decision Process

### The Core of a Blog CMS

**Current State:**
- User authentication complete
- Category entity created
- Tag entity created
- But no posts yet - the main content!

**Goal:**
- Create Post entity (the heart of our CMS)
- Establish relationships with User, Category, and Tags
- Support draft/published workflow
- Enable soft delete for content recovery
- Denormalized counters for performance

**Teaching Point:** The Post entity is the most complex entity in our system. It has relationships to multiple other entities and contains the business logic for content publishing. Getting this right is crucial.

---

## Decision 1: Entity Relationships Overview

### The Data Model

```
┌─────────┐       ┌─────────┐       ┌──────────┐
│  User   │       │  Post   │       │ Category │
│─────────│       │─────────│       │──────────│
│ id      │◄──────│ user_id │       │ id       │
│ name    │  1:N  │ cat_id  │──────►│ name     │
│ email   │       │ title   │  N:1  │ slug     │
└─────────┘       │ content │       └──────────┘
                  │ ...     │
                  └────┬────┘
                       │
                       │ N:M
                       ▼
                  ┌─────────┐
                  │post_tags│
                  │─────────│
                  │ post_id │
                  │ tag_id  │
                  └────┬────┘
                       │
                       ▼
                  ┌─────────┐
                  │   Tag   │
                  │─────────│
                  │ id      │
                  │ name    │
                  │ slug    │
                  └─────────┘
```

### Relationship Summary

| Relationship | Type | Description |
|--------------|------|-------------|
| Post → User | ManyToOne | Every post has one author |
| Post → Category | ManyToOne | Post belongs to one category (optional) |
| Post ↔ Tag | ManyToMany | Post can have many tags, tag can be on many posts |

---

## Decision 2: Post-User Relationship (Author)

### The Relationship

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "user_id", nullable = false)
private User user;
```

### Why ManyToOne?

**One User → Many Posts:**
- An author can write many posts
- Each post has exactly one author

**Real-world analogy:**
- A newspaper columnist (User) writes many articles (Posts)
- Each article has one byline (author)

### Why LAZY Fetch?

```java
fetch = FetchType.LAZY
```

**EAGER (default for @ManyToOne):**
```java
Post post = postRepository.findById(1);
// Hibernate executes: SELECT * FROM posts WHERE id = 1
// ALSO executes: SELECT * FROM users WHERE id = post.user_id
// User loaded even if you don't need it!
```

**LAZY (our choice):**
```java
Post post = postRepository.findById(1);
// Hibernate executes: SELECT * FROM posts WHERE id = 1
// User NOT loaded yet

String authorName = post.getUser().getName();
// NOW Hibernate executes: SELECT * FROM users WHERE id = post.user_id
```

**Why LAZY is better:**
- Load data only when needed
- Better performance for list views
- Reduces memory usage
- Prevents N+1 if you're careful

**Teaching Point:** Always use LAZY for @ManyToOne. EAGER causes performance problems that are hard to debug later.

### Why nullable = false?

```java
@JoinColumn(name = "user_id", nullable = false)
```

- Every post MUST have an author
- Cannot create orphan posts
- Database enforces this (NOT NULL constraint)

**What about deleted users?**
- Soft delete users (isDeleted = true)
- Posts remain, author shows as "Deleted User"
- Or transfer posts to admin account

---

## Decision 3: Post-Category Relationship

### The Relationship

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "category_id")
private Category category;
```

### Why Single Category (ManyToOne) vs Multiple (ManyToMany)?

**Option 1: ManyToMany (Multiple Categories)**
```java
@ManyToMany
private Set<Category> categories;
// Post in "Technology" AND "Business" AND "Tutorial"
```

**Option 2: ManyToOne (Single Category) - Our Choice**
```java
@ManyToOne
private Category category;
// Post in "Technology" only
```

**Why single category?**

1. **Clearer Organization:**
    - "Where does this post live?" has one answer
    - No confusion about primary category

2. **Simpler Navigation:**
    - Blog sections are clear
    - /technology shows technology posts only

3. **Industry Standard:**
    - WordPress: One category (can add more, but one primary)
    - Medium: One publication
    - Most blogs: One main category

4. **Tags for Cross-Cutting:**
    - Use tags for multiple topics
    - Category = section, Tags = topics

**Teaching Point:** Categories answer "WHERE does this content live?" Tags answer "WHAT is this content about?" One location, many topics.

### Why Nullable Category?

```java
@JoinColumn(name = "category_id")  // No nullable = false
```

- Category is optional
- Drafts might not have category yet
- "Uncategorized" is a valid state
- Can enforce category on publish

---

## Decision 4: Post-Tag Relationship (ManyToMany)

### The Relationship

```java
@ManyToMany
@JoinTable(
    name = "post_tags",
    joinColumns = @JoinColumn(name = "post_id"),
    inverseJoinColumns = @JoinColumn(name = "tag_id")
)
@Builder.Default
private Set<Tag> tags = new HashSet<>();
```

### Understanding ManyToMany

**The Problem:**
- One post can have many tags
- One tag can be on many posts
- Relational databases can't store this directly

**The Solution: Junction Table**

```
posts                post_tags              tags
┌────┬───────┐      ┌─────────┬────────┐   ┌────┬─────────────┐
│ id │ title │      │ post_id │ tag_id │   │ id │ name        │
├────┼───────┤      ├─────────┼────────┤   ├────┼─────────────┤
│ 1  │ Post1 │      │ 1       │ 1      │   │ 1  │ java        │
│ 2  │ Post2 │      │ 1       │ 2      │   │ 2  │ spring-boot │
└────┴───────┘      │ 2       │ 2      │   │ 3  │ tutorial    │
                    │ 2       │ 3      │   └────┴─────────────┘
                    └─────────┴────────┘

Post 1 has tags: java, spring-boot
Post 2 has tags: spring-boot, tutorial
```

### @JoinTable Explained

```java
@JoinTable(
    name = "post_tags",                              // Junction table name
    joinColumns = @JoinColumn(name = "post_id"),    // FK to this entity (Post)
    inverseJoinColumns = @JoinColumn(name = "tag_id") // FK to other entity (Tag)
)
```

### Why Set Instead of List?

```java
private Set<Tag> tags = new HashSet<>();  // ✓ Good
private List<Tag> tags = new ArrayList<>(); // ✗ Avoid
```

**Set advantages:**
- No duplicates (can't add same tag twice)
- Better performance for contains() checks
- JPA handles ManyToMany better with Set

**List problems:**
- Hibernate uses bag semantics
- Removing items is inefficient
- Can accidentally have duplicates

**Teaching Point:** Always use Set for ManyToMany relationships.

### Why @Builder.Default?

```java
@Builder.Default
private Set<Tag> tags = new HashSet<>();
```

**Without @Builder.Default:**
```java
Post post = Post.builder()
    .title("My Post")
    .build();
post.getTags(); // Returns NULL! Builder ignores field initializer
```

**With @Builder.Default:**
```java
Post post = Post.builder()
    .title("My Post")
    .build();
post.getTags(); // Returns empty HashSet ✓
```

**Teaching Point:** Lombok's @Builder ignores field initializers. Use @Builder.Default for fields with default values.

---

## Decision 5: PostStatus Enum

### The Enum

```java
public enum PostStatus {
    DRAFT,
    PUBLISHED
}
```

### Why Only Two States?

**Considered alternatives:**

```java
// More complex workflow
enum PostStatus {
    DRAFT,
    PENDING_REVIEW,
    APPROVED,
    PUBLISHED,
    ARCHIVED,
    SCHEDULED
}
```

**Why we chose simple:**

1. **YAGNI:** Most blogs need only draft/published
2. **Simplicity:** Easier to understand and maintain
3. **Extensible:** Can add states later if needed

**When to add more states:**
- Multi-author review workflow → PENDING_REVIEW
- Scheduled publishing → SCHEDULED
- Content archiving → ARCHIVED

### Using the Enum

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false, length = 20)
private PostStatus status;
```

**EnumType.STRING:**
- Stores "DRAFT" or "PUBLISHED" in database
- Human-readable
- Safe to reorder enum values

---

## Decision 6: Denormalized Counters

### View Count and Like Count

```java
@Column(nullable = false)
@Builder.Default
private Integer viewCount = 0;

@Column(nullable = false)
@Builder.Default
private Integer likeCount = 0;
```

### Why Denormalize?

**Normalized approach (counting every time):**
```sql
SELECT COUNT(*) FROM post_views WHERE post_id = 1;
SELECT COUNT(*) FROM post_likes WHERE post_id = 1;
-- Slow for popular posts with millions of views
```

**Denormalized approach (storing count):**
```sql
SELECT view_count, like_count FROM posts WHERE id = 1;
-- Always fast, regardless of popularity
```

### Trade-offs

| Aspect | Normalized | Denormalized |
|--------|------------|--------------|
| Read speed | Slow (COUNT) | Fast (single column) |
| Write speed | Fast (INSERT) | Slower (UPDATE counter) |
| Accuracy | Always accurate | Can drift if buggy |
| Complexity | Simple | Need to maintain sync |

**For a blog:**
- Reads >> Writes (views happen constantly)
- Slight count inaccuracy is acceptable
- Performance matters for popular posts

**Teaching Point:** Denormalization is a performance optimization. Use it when read performance matters more than perfect accuracy.

### Keeping Counters in Sync

```java
// When user views post
@Transactional
public void incrementViewCount(Long postId) {
    postRepository.incrementViewCount(postId);
}

// In repository
@Modifying
@Query("UPDATE Post p SET p.viewCount = p.viewCount + 1 WHERE p.id = :id")
void incrementViewCount(@Param("id") Long id);
```

---

## Decision 7: Content Fields

### Title

```java
@Column(nullable = false, length = 200)
private String title;
```

- **200 characters:** Generous for headlines
- **Required:** Every post needs a title
- SEO best practice: 50-60 chars, but allow longer

### Slug

```java
@Column(nullable = false, unique = true, length = 250)
private String slug;
```

- **URL-friendly identifier**
- **Unique:** No duplicate URLs
- **250 chars:** Title (200) + potential suffix (-2, -3)

**Example:**
```
Title: "Getting Started with Spring Boot in 2024"
Slug: "getting-started-with-spring-boot-in-2024"
URL: /posts/getting-started-with-spring-boot-in-2024
```

### Content

```java
@Column(nullable = false, columnDefinition = "TEXT")
private String content;
```

- **TEXT type:** Unlimited length (practically)
- **Required:** Can't have empty posts
- Will store HTML or Markdown

**Why columnDefinition = "TEXT"?**
- Default VARCHAR has length limits
- TEXT allows unlimited content
- PostgreSQL TEXT is efficient

### Excerpt

```java
@Column(length = 500)
private String excerpt;
```

- **Short summary** for listings/previews
- **Optional:** Can auto-generate from content
- **500 chars:** Enough for 2-3 sentences

### Featured Image URL

```java
@Column(length = 255)
private String featuredImageUrl;
```

- **URL to image** (not the image itself)
- **Optional:** Not all posts need images
- Hosted on CDN/cloud storage

---

## Decision 8: Timestamp Fields

### publishedAt vs createdAt

```java
private LocalDateTime publishedAt;  // When made public

@CreationTimestamp
@Column(nullable = false, updatable = false)
private LocalDateTime createdAt;    // When created

@UpdateTimestamp
@Column(nullable = false)
private LocalDateTime updatedAt;    // Last modified
```

**Why separate publishedAt?**

**Scenario:**
1. Author creates draft on Monday (createdAt = Monday)
2. Author edits on Tuesday (updatedAt = Tuesday)
3. Author publishes on Friday (publishedAt = Friday)

**Display logic:**
- "Published on Friday" (use publishedAt)
- Not "Created on Monday" (wrong impression)

**publishedAt is nullable:**
- Drafts don't have publish date
- Set when status changes to PUBLISHED

---

## Decision 9: Soft Delete

```java
@Column(nullable = false)
@Builder.Default
private Boolean isDeleted = false;
```

### Why Soft Delete for Posts?

1. **Content Recovery:** Accidental deletion is common
2. **Audit Trail:** Know what was deleted and when
3. **SEO:** Can redirect deleted URLs properly
4. **Legal:** Some content must be retained

### Query Considerations

```java
// Always filter deleted posts
@Query("SELECT p FROM Post p WHERE p.isDeleted = false")
List<Post> findAllActive();

// Admin can see deleted
@Query("SELECT p FROM Post p WHERE p.isDeleted = true")
List<Post> findAllDeleted();
```

---

## Decision 10: Database Migrations

### V4: Posts Table

```sql
CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    category_id BIGINT REFERENCES categories(id),
    title VARCHAR(200) NOT NULL,
    slug VARCHAR(250) UNIQUE NOT NULL,
    content TEXT NOT NULL,
    excerpt VARCHAR(500),
    featured_image_url VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    view_count INTEGER NOT NULL DEFAULT 0,
    like_count INTEGER NOT NULL DEFAULT 0,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### Index Strategy

```sql
-- Author's posts
CREATE INDEX idx_posts_user_id ON posts(user_id);

-- Category filtering
CREATE INDEX idx_posts_category_id ON posts(category_id);

-- URL lookups
CREATE INDEX idx_posts_slug ON posts(slug);

-- Status filtering
CREATE INDEX idx_posts_status ON posts(status);

-- Soft delete filtering
CREATE INDEX idx_posts_is_deleted ON posts(is_deleted);

-- Chronological sorting
CREATE INDEX idx_posts_published_at ON posts(published_at);

-- Most common query pattern
CREATE INDEX idx_posts_published_active ON posts(status, is_deleted, published_at DESC);
```

### Why Composite Index?

```sql
CREATE INDEX idx_posts_published_active ON posts(status, is_deleted, published_at DESC);
```

**Most common query:**
```sql
SELECT * FROM posts
WHERE status = 'PUBLISHED'
  AND is_deleted = false
ORDER BY published_at DESC;
```

**Without composite index:** Uses multiple indexes, slower
**With composite index:** Single index scan, much faster

**Teaching Point:** Create composite indexes for your most common query patterns. Order matters: filter columns first, sort column last.

### V5: Post-Tags Junction Table

```sql
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);

CREATE INDEX idx_post_tags_tag_id ON post_tags(tag_id);
```

### Why CASCADE Delete?

```sql
REFERENCES posts(id) ON DELETE CASCADE
REFERENCES tags(id) ON DELETE CASCADE
```

**When post is deleted:**
- All post_tags entries for that post are deleted
- Tags themselves remain (other posts may use them)

**When tag is deleted:**
- All post_tags entries for that tag are deleted
- Posts themselves remain (just lose that tag)

**Teaching Point:** CASCADE on junction tables is usually correct. The relationship is deleted, not the entities.

### Why Index on tag_id?

```sql
CREATE INDEX idx_post_tags_tag_id ON post_tags(tag_id);
```

**Primary key (post_id, tag_id):**
- Already indexes by post_id (leftmost column)
- "Get all tags for post X" is fast

**Additional index on tag_id:**
- "Get all posts for tag Y" needs this
- Tag pages: `/tags/java` → all posts with java tag

---

## Files Created

### 1. src/main/java/com/studylink/blogcms/enums/PostStatus.java

```java
package com.studylink.blogcms.enums;

public enum PostStatus {
    DRAFT,
    PUBLISHED
}
```

### 2. src/main/java/com/studylink/blogcms/entity/Post.java

```java
package com.studylink.blogcms.entity;

import com.studylink.blogcms.enums.PostStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, unique = true, length = 250)
    private String slug;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 500)
    private String excerpt;

    @Column(length = 255)
    private String featuredImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostStatus status;

    @Column(nullable = false)
    @Builder.Default
    private Integer viewCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer likeCount = 0;

    @ManyToMany
    @JoinTable(
        name = "post_tags",
        joinColumns = @JoinColumn(name = "post_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @Builder.Default
    private Set<Tag> tags = new HashSet<>();

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    private LocalDateTime publishedAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
```

### 3. src/main/resources/db/migrations/V4__create_posts_table.sql

```sql
-- V4: Create posts table
CREATE TABLE posts (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    category_id BIGINT REFERENCES categories(id),
    title VARCHAR(200) NOT NULL,
    slug VARCHAR(250) UNIQUE NOT NULL,
    content TEXT NOT NULL,
    excerpt VARCHAR(500),
    featured_image_url VARCHAR(255),
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    view_count INTEGER NOT NULL DEFAULT 0,
    like_count INTEGER NOT NULL DEFAULT 0,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_posts_user_id ON posts(user_id);
CREATE INDEX idx_posts_category_id ON posts(category_id);
CREATE INDEX idx_posts_slug ON posts(slug);
CREATE INDEX idx_posts_status ON posts(status);
CREATE INDEX idx_posts_is_deleted ON posts(is_deleted);
CREATE INDEX idx_posts_published_at ON posts(published_at);
CREATE INDEX idx_posts_published_active ON posts(status, is_deleted, published_at DESC);
```

### 4. src/main/resources/db/migrations/V5__create_post_tags_table.sql

```sql
-- V5: Create post_tags junction table for ManyToMany relationship
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
    tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (post_id, tag_id)
);

CREATE INDEX idx_post_tags_tag_id ON post_tags(tag_id);
```

---

## What Works Now After This Commit?

### Database State:

```
Tables:
├── users (V1)
├── categories (V2)
├── tags (V3)
├── posts (V4)  ← NEW
└── post_tags (V5)  ← NEW
```

### Entity Relationships:

```java
// Create a complete post
User author = userRepository.findById(1L).get();
Category category = categoryRepository.findById(1L).get();
Tag javaTag = tagRepository.findById(1L).get();
Tag springTag = tagRepository.findById(2L).get();

Post post = Post.builder()
    .user(author)
    .category(category)
    .title("Getting Started with Spring Boot")
    .slug("getting-started-with-spring-boot")
    .content("<p>Spring Boot makes Java development fun...</p>")
    .excerpt("Learn how to build your first Spring Boot application")
    .status(PostStatus.DRAFT)
    .tags(Set.of(javaTag, springTag))
    .build();

postRepository.save(post);
```

### Still Needed:

- **PostRepository** - Spring Data JPA interface
- **PostService** - Business logic (publish, soft delete)
- **PostController** - REST endpoints
- **DTOs** - Request/Response objects
- **Bidirectional relationships** - Optional inverse mappings

---

## Common Pitfalls to Avoid

### 1. N+1 Query Problem

**Bad:**
```java
List<Post> posts = postRepository.findAll();
for (Post post : posts) {
    System.out.println(post.getUser().getName()); // N additional queries!
}
```

**Good:**
```java
@Query("SELECT p FROM Post p JOIN FETCH p.user")
List<Post> findAllWithUser();
```

### 2. Circular JSON Serialization

**Problem:**
```java
// Post has User, User might have Posts
// Jackson tries to serialize infinitely
```

**Solutions:**
```java
// Option 1: @JsonIgnore
@JsonIgnore
private User user;

// Option 2: Use DTOs (recommended)
public class PostResponseDto {
    private Long authorId;
    private String authorName;
    // Not the full User object
}
```

### 3. Forgetting @Builder.Default

**Wrong:**
```java
private Set<Tag> tags = new HashSet<>();  // Ignored by builder!
```

**Right:**
```java
@Builder.Default
private Set<Tag> tags = new HashSet<>();
```

---

## Architectural Decisions Record

**ADR-022: Single Category per Post (ManyToOne)**
- Status: Accepted
- Context: Decide between single or multiple categories per post
- Decision: Single category, use tags for multiple topics
- Consequences: Clearer organization, tags handle cross-cutting topics

**ADR-023: Denormalized View/Like Counters**
- Status: Accepted
- Context: Need to display view and like counts efficiently
- Decision: Store counts directly on Post entity
- Consequences: Fast reads, must maintain sync on writes

**ADR-024: LAZY Fetch for All Relationships**
- Status: Accepted
- Context: Decide fetch strategy for entity relationships
- Decision: LAZY fetch for all @ManyToOne and @ManyToMany
- Consequences: Better performance, must handle lazy loading in service layer

**ADR-025: Composite Index for Common Query**
- Status: Accepted
- Context: Optimize for "get published posts" query
- Decision: Composite index on (status, is_deleted, published_at)
- Consequences: Fast homepage/listing queries

---

## Commit Stats

- **Files created:** 4
    - PostStatus.java enum (6 lines)
    - Post.java entity (72 lines)
    - V4__create_posts_table.sql (32 lines)
    - V5__create_post_tags_table.sql (9 lines)
- **Lines of code:** ~119
- **Tables created:** 2 (posts, post_tags)
- **Indexes created:** 8

---

## Next Steps

After this commit, we should:
1. **Add bidirectional mappings** (optional) - User.posts, Category.posts, Tag.posts
2. **Create PostRepository** - Spring Data JPA interface
3. **Create SlugService** - Generate unique slugs
4. **Create PostService** - Business logic
5. **Create PostController** - CRUD endpoints
6. **Create DTOs** - Request/Response objects

---

## Resources for Students

- JPA Relationships: https://www.baeldung.com/jpa-joincolumn-vs-mappedby
- N+1 Problem: https://www.baeldung.com/hibernate-common-performance-problems-in-logs
- ManyToMany Best Practices: https://vladmihalcea.com/the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate/
- Database Indexing: https://use-the-index-luke.com/
- Soft Delete Pattern: https://www.baeldung.com/spring-jpa-soft-delete
