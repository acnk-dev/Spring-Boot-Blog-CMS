# Commit 27: Create TagController

**Date:** March 20, 2026
**Commit Message:** Create TagController for tag endpoints

---

## Overview

This commit adds `TagController` — the HTTP entry point for tag management in the Blog CMS. Tags are flat, multi-value labels that authors attach to posts to improve discoverability (e.g., a post about "deploying Spring Boot on AWS" might have tags: `spring-boot`, `aws`, `docker`, `devops`).

### What this commit adds

| Method | Path | Auth | Status |
|--------|------|------|--------|
| POST | `/api/v1/tags` | ADMIN only | 201 Created |
| GET | `/api/v1/tags` | Public | 200 OK |
| GET | `/api/v1/tags/{id}` | Public | 200 OK |
| DELETE | `/api/v1/tags/{id}` | ADMIN only | 204 No Content |

### Why this commit matters

`TagService` already has full business logic. Without `TagController`, that logic is unreachable from HTTP. This controller is intentionally simpler than `CategoryController` — tags are lightweight labels with fewer lifecycle concerns.

---

## Tags vs Categories — What's the Difference?

This is one of the most common questions in content system design. Both organize posts, but they serve different purposes.

### Categories — Hierarchical, Exclusive

| Property | Detail |
|----------|--------|
| Structure | Tree / hierarchy (parent → child) |
| Cardinality | One per post (a post belongs to one category) |
| Size | Small, curated list (~5–20 categories) |
| Ownership | ADMIN-defined, stable |
| Examples | "Backend", "Frontend", "DevOps", "Career" |

Categories answer: **"What is this post about, broadly?"**

A post about deploying a Spring Boot app to AWS belongs to **"Backend"** — not both "Backend" and "DevOps" at the same time (in a single-category model).

---

### Tags — Flat, Additive

| Property | Detail |
|----------|--------|
| Structure | Flat (no hierarchy) |
| Cardinality | Many per post (a post can have 1–10 tags) |
| Size | Large, growing list (~50–500+ tags) |
| Ownership | ADMIN or AUTHOR-created, dynamic |
| Examples | `spring-boot`, `aws`, `docker`, `jwt`, `postgresql` |

Tags answer: **"What specific topics does this post touch?"**

The same post belongs to category **"Backend"** and carries tags `spring-boot`, `aws`, `docker`, `deployment`.

---

### Visual Model

```
Category (one per post, hierarchical):
    Backend
    ├── Java
    │   └── Spring Boot   ← this post lives here
    └── Node.js

Tags (many per post, flat):
    spring-boot  aws  docker  deployment  tutorial
         ↑        ↑      ↑        ↑           ↑
                    all on this post
```

---

### When to Use Each

| Use Case | Category | Tag |
|----------|----------|-----|
| Main navigation menu | ✅ | ❌ |
| Tag cloud widget | ❌ | ✅ |
| Filter sidebar (broad) | ✅ | ❌ |
| Full-text search assist | ❌ | ✅ |
| RSS feeds by topic | ✅ | ✅ |
| Related posts widget | ❌ | ✅ |
| Analytics grouping | ✅ | ❌ |

---

## Design Decisions

### Decision 1: No Update Endpoint

`TagController` has no `PUT /{id}` endpoint. This is intentional.

**The problem with updating tags:**
Imagine tag `spring-boot` gets renamed to `springboot` (no hyphen). Suddenly:
- All existing posts with `spring-boot` now have `springboot`
- URLs that referenced `/tags/spring-boot` break silently
- Search indexes using the old slug become stale
- Authors who bookmarked `/posts?tag=spring-boot` get no results

**The safe alternative:** Delete the old tag, create the new one. Authors then manually update their posts. This is more work but keeps state explicit — nothing changes behind the scenes.

**Why this is acceptable:**
- Tags are renamed rarely (admin decision, not routine)
- The cost of a subtle bug (silent slug change) is higher than the cost of a manual two-step
- Post counts drop to zero on deleted tags immediately, making the impact visible

```
# Wrong: rename silently
PUT /api/v1/tags/5  { "name": "springboot" }
→ All posts silently switch from "spring-boot" to "springboot"

# Right: explicit delete + create
DELETE /api/v1/tags/5           → removes "spring-boot" from all posts
POST   /api/v1/tags { "name": "springboot" }  → fresh tag, no posts yet
# Authors re-tag their posts intentionally
```

---

### Decision 2: Public Listing

`GET /api/v1/tags` requires no authentication. This enables:
- **Tag clouds** in the blog sidebar (rendered server-side or via JS fetch)
- **SEO-friendly tag pages** (Google can crawl `/posts?tag=spring-boot`)
- **Autocomplete** when an author types a tag name in the post editor

If listing required a token, the blog's public UI would need to authenticate just to show a sidebar widget — that's unnecessary friction.

---

### Decision 3: ADMIN-Only Write Operations

Same reasoning as `CategoryController`. Tags define discoverability metadata. Allowing any AUTHOR to freely create tags leads to:
- Tag sprawl (`spring-boot`, `SpringBoot`, `spring boot`, `Spring-Boot` as four separate tags)
- Orphaned tags that no post uses
- Inconsistent naming conventions

In practice, there are two common models:
1. **ADMIN-only** (our choice): Stricter, cleaner taxonomy, requires admin involvement
2. **AUTHOR-can-create**: More flexible, requires a tag moderation/merge tool

We chose option 1 for simplicity. `TagService.getOrCreateTags()` exists if this policy needs to change — it's a service-level decision, not a controller-level constraint.

---

## API Endpoints

### POST /api/v1/tags

**Authorization:** ADMIN only (`Authorization: Bearer <admin-token>`)
**Status:** 201 Created

**Request:**
```json
{
  "name": "spring-boot"
}
```

**Validation rules:**
- `name`: required, 2–50 characters

**Response (201):**
```json
{
  "id": 7,
  "name": "spring-boot",
  "slug": "spring-boot",
  "createdAt": "2026-03-20T14:30:00",
  "updatedAt": "2026-03-20T14:30:00"
}
```

**Response (409 Conflict — duplicate name):**
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "Tag already exists with name: spring-boot"
}
```

---

### GET /api/v1/tags

**Authorization:** Public (no token needed)
**Status:** 200 OK

**Response:**
```json
[
  {
    "id": 1,
    "name": "aws",
    "slug": "aws",
    "createdAt": "2026-03-01T09:00:00",
    "updatedAt": "2026-03-01T09:00:00"
  },
  {
    "id": 2,
    "name": "docker",
    "slug": "docker",
    "createdAt": "2026-03-01T09:05:00",
    "updatedAt": "2026-03-01T09:05:00"
  },
  {
    "id": 7,
    "name": "spring-boot",
    "slug": "spring-boot",
    "createdAt": "2026-03-20T14:30:00",
    "updatedAt": "2026-03-20T14:30:00"
  }
]
```

Tags are returned alphabetically (ordered by name in `TagService`).

---

### GET /api/v1/tags/{id}

**Authorization:** Public
**Status:** 200 OK | 404 Not Found

**Example:** `GET /api/v1/tags/7`

**Response (200):**
```json
{
  "id": 7,
  "name": "spring-boot",
  "slug": "spring-boot",
  "createdAt": "2026-03-20T14:30:00",
  "updatedAt": "2026-03-20T14:30:00"
}
```

**Response (404):**
```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Tag not found with id: 99"
}
```

---

### DELETE /api/v1/tags/{id}

**Authorization:** ADMIN only
**Status:** 204 No Content | 404 Not Found

**Example:** `DELETE /api/v1/tags/7`

**Response (204):** Empty body.

**Side effect:** The tag is removed from the `post_tags` junction table for every post that referenced it. Posts themselves are not deleted or modified — they simply lose this tag.

---

## Code Flow Diagram

```
HTTP Request
    │
    ▼
┌───────────────────────────┐
│  JwtAuthenticationFilter  │  ← validates Bearer token (if present)
│  Sets SecurityContext      │  ← public endpoints: context stays anonymous
└───────────────────────────┘
    │
    ▼
┌───────────────────────────┐
│      TagController        │
│  @PreAuthorize check      │  ← ADMIN guard on POST and DELETE
│  @Valid @RequestBody      │  ← validates CreateTagRequest constraints
│  @PathVariable id         │  ← extracts /tags/{id} from URL
└───────────────────────────┘
    │
    ▼
┌───────────────────────────┐
│      TagService           │  ← business logic
│   (TagServiceImpl)        │  ← slug generation, duplicate check
│                           │  ← for delete: disassociates from posts
└───────────────────────────┘
    │
    ▼
┌───────────────────────────┐
│    TagRepository          │  ← Spring Data JPA
│    (JPA/Hibernate)        │  ← findAll, findById, save, deleteById
└───────────────────────────┘
    │
    ▼
┌───────────────────────────┐
│      PostgreSQL           │
│   tags table              │  ← id, name, slug, created_at, updated_at
│   post_tags table         │  ← post_id, tag_id (junction)
└───────────────────────────┘
    │
    ▼  (reverse path: Tag entity → TagResponse DTO → JSON)
HTTP Response
```

---

## Many-to-Many Relationship: Post ↔ Tag

### How It Works in JPA

A single post can have many tags. A single tag can belong to many posts. This is a **many-to-many** relationship, modeled with a **junction table**.

**In `Post.java`:**
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

**In `Tag.java`:**
```java
@ManyToMany(mappedBy = "tags")
@Builder.Default
private Set<Post> posts = new HashSet<>();
```

`Post` is the **owning side** (it has `@JoinTable`). `Tag` is the **inverse side** (it has `mappedBy`). JPA writes to the junction table only via the owning side.

---

### The Junction Table

```sql
-- post_tags (junction table)
CREATE TABLE post_tags (
    post_id BIGINT NOT NULL REFERENCES posts(id),
    tag_id  BIGINT NOT NULL REFERENCES tags(id),
    PRIMARY KEY (post_id, tag_id)
);
```

**Example data:**

| post_id | tag_id |
|---------|--------|
| 1 | 2 (docker) |
| 1 | 7 (spring-boot) |
| 1 | 3 (aws) |
| 2 | 7 (spring-boot) |
| 2 | 5 (java) |

Post 1 has tags: docker, spring-boot, aws.
Post 2 has tags: spring-boot, java.
Tag "spring-boot" appears in both posts.

---

### What Happens on Tag Delete

When `DELETE /api/v1/tags/7` is called:

1. JPA removes all rows from `post_tags` where `tag_id = 7`
2. JPA then deletes the row from `tags` where `id = 7`
3. Posts 1 and 2 lose the "spring-boot" tag — but the posts themselves remain unchanged

```sql
-- JPA executes these in a transaction:
DELETE FROM post_tags WHERE tag_id = 7;
DELETE FROM tags WHERE id = 7;
```

This is a **hard delete** for tags (unlike posts, which use soft delete). Tags have no audit or recovery requirement — if a tag is gone, it's gone.

---

### Why `Set<Tag>` and Not `List<Tag>`?

```java
private Set<Tag> tags = new HashSet<>();  // ✅ correct
private List<Tag> tags = new ArrayList<>();  // ⚠️ avoid for @ManyToMany
```

With Hibernate and `@ManyToMany`, using `List` can trigger the **"bag delete" problem**: Hibernate deletes ALL rows from the junction table and re-inserts the remaining ones on every update. With `Set`, Hibernate computes the diff and only removes/adds the changed rows.

**Teaching Point:** Always use `Set` for `@ManyToMany` collections in Hibernate.

---

## Testing Guide

### Prerequisites
- Application running on `http://localhost:8081`
- ADMIN JWT token from `POST /api/v1/auth/login`

```bash
# Get ADMIN token
TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"your-password"}' \
  | jq -r '.accessToken')
```

---

### Test 1: Create a Tag (ADMIN)

```bash
curl -X POST http://localhost:8081/api/v1/tags \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "spring-boot"}'
```

**Expected:** 201 with `{"id":1,"name":"spring-boot","slug":"spring-boot",...}`

---

### Test 2: Try to Create Duplicate Tag (should fail)

```bash
curl -X POST http://localhost:8081/api/v1/tags \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "spring-boot"}'
```

**Expected:** 409 Conflict — tag name already exists.

---

### Test 3: Create Tag Without Token (should fail)

```bash
curl -X POST http://localhost:8081/api/v1/tags \
  -H "Content-Type: application/json" \
  -d '{"name": "java"}'
```

**Expected:** 401 Unauthorized — no token provided.

---

### Test 4: Get All Tags (Public)

```bash
curl http://localhost:8081/api/v1/tags
```

**Expected:** 200 with JSON array of all tags. No token needed.

---

### Test 5: Get Tag by ID (Public)

```bash
curl http://localhost:8081/api/v1/tags/1
```

**Expected:** 200 with the "spring-boot" tag JSON.

---

### Test 6: Get Nonexistent Tag (should fail)

```bash
curl http://localhost:8081/api/v1/tags/999
```

**Expected:** 404 Not Found.

---

### Test 7: Delete a Tag (ADMIN)

```bash
curl -X DELETE http://localhost:8081/api/v1/tags/1 \
  -H "Authorization: Bearer $TOKEN"
```

**Expected:** 204 No Content (empty body).

---

### Test 8: Delete With Author Token (should fail)

```bash
AUTHOR_TOKEN=$(curl -s -X POST http://localhost:8081/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"author@example.com","password":"password"}' \
  | jq -r '.accessToken')

curl -X DELETE http://localhost:8081/api/v1/tags/2 \
  -H "Authorization: Bearer $AUTHOR_TOKEN"
```

**Expected:** 403 Forbidden — AUTHOR role cannot delete tags.

---

### Test 9: Validate Constraint (name too short)

```bash
curl -X POST http://localhost:8081/api/v1/tags \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name": "x"}'
```

**Expected:** 400 Bad Request — `"Name must be between 2 and 50 characters"`.

---

## Best Practices

### Tag Naming Conventions

Consistent tag names make search and filtering reliable. Establish these rules before the tag list grows:

| Rule | Good | Bad |
|------|------|-----|
| Lowercase only | `spring-boot` | `Spring-Boot`, `SPRING_BOOT` |
| Hyphens for spaces | `spring-boot` | `spring_boot`, `springboot` |
| No version numbers (usually) | `java` | `java-21`, `java21` |
| Singular, not plural | `tutorial` | `tutorials` |
| Technology, not opinion | `docker` | `docker-is-great` |

**Why lowercase + hyphens?**
- Matches URL slug format: tag `spring-boot` → URL `/posts?tag=spring-boot`
- No case-sensitivity bugs (`Java` ≠ `java` in some DB collations)
- Consistent with common tagging systems (GitHub, Stack Overflow, DEV.to)

---

### When to Create New Tags

Create a new tag when:
- It represents a distinct technology, tool, or concept
- At least 3 future posts will plausibly use it
- It doesn't overlap significantly with an existing tag

Avoid creating tags for:
- One-off events (`blog-launch-2026`)
- Overly broad concepts already covered by categories (`backend`, `frontend`)
- Personal opinions (`best-practices`, `must-read`)

**Rule of thumb:** If you're unsure, wait until a second post needs the tag.

---

### Tag Consolidation Strategies

Over time, tags accumulate synonyms and typos. Strategies to manage this:

**1. Merge (manual, current approach)**
1. Identify duplicate tags (e.g., `postgres` and `postgresql`)
2. Re-tag all posts with `postgres` to also have `postgresql`
3. Delete `postgres`
4. Result: all posts use the canonical tag `postgresql`

**2. Alias table (future enhancement)**
Store a mapping: `postgres → postgresql`. Tag lookups resolve aliases transparently. Requires a new DB table and service logic.

**3. Tag search with normalization (current service support)**
`TagService.findTagsByPrefix()` supports autocomplete — surfacing `postgresql` when an admin types `post`. This reduces duplicates at creation time.

**4. Periodic audit**
Run a query for tags with zero posts and delete them:
```sql
SELECT t.id, t.name
FROM tags t
LEFT JOIN post_tags pt ON t.id = pt.tag_id
WHERE pt.tag_id IS NULL;
```
Tags with no posts are orphans — safe to delete.

---

## Common Issues

### 403 Forbidden — Insufficient Role

```json
{ "status": 403, "error": "Forbidden", "message": "Access Denied" }
```

**Cause:** POST or DELETE attempted with SUBSCRIBER or AUTHOR token, or no token.
**Fix:** Use an ADMIN account token.

---

### 404 Not Found — Tag Doesn't Exist

```json
{ "status": 404, "error": "Not Found", "message": "Tag not found with id: 99" }
```

**Cause:** Wrong ID or the tag was already deleted.
**Fix:** Call `GET /api/v1/tags` to see valid IDs.

---

### 400 Bad Request — Validation Failed

```json
{
  "status": 400,
  "errors": { "name": "Name must be between 2 and 50 characters" }
}
```

**Cause:** `name` is blank, too short (< 2 chars), or too long (> 50 chars).
**Fix:** Ensure tag name is 2–50 characters.

---

### 409 Conflict — Duplicate Tag Name

```json
{ "status": 409, "error": "Conflict", "message": "Tag already exists with name: spring-boot" }
```

**Cause:** A tag with this name already exists (unique constraint on `tags.name`).
**Fix:** Use `GET /api/v1/tags` to check existing tags before creating.

---

## Commit Stats

| Metric | Value |
|--------|-------|
| New files | 1 |
| File | `TagController.java` |
| Lines of code | ~95 |
| Endpoints added | 4 |
| Public endpoints | 2 |
| Admin-only endpoints | 2 |

---

## What Works Now

- Full tag CRUD (create, read, delete) via HTTP
- Public listing and get-by-ID
- ADMIN-only create and delete
- Automatic slug generation at create time
- Tag safely removed from all posts on delete
- Input validation with descriptive error messages

## What's Missing (Next Steps)

- `GET /api/v1/tags/slug/{slug}` — retrieve by slug for SEO-friendly URLs (service method `getTagBySlug()` already exists, just not exposed)
- `GET /api/v1/tags/popular?limit=10` — tag cloud endpoint (service method `getPopularTags()` already exists)
- `GET /api/v1/tags/search?q=spring` — search tags by name (service method `searchTags()` already exists)
- Integration tests covering the many-to-many cascade behavior on delete

---

## Resources

- [Hibernate Many-to-Many best practices — Vlad Mihalcea](https://vladmihalcea.com/the-best-way-to-use-the-manytomany-annotation-with-jpa-and-hibernate/)
- [Spring Security @PreAuthorize](https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html)
- [REST API design — choosing HTTP status codes](https://www.rfc-editor.org/rfc/rfc7231)
- [Tag vs Category — Content taxonomy patterns](https://yoast.com/categories-vs-tags-seo-wordpress/)
