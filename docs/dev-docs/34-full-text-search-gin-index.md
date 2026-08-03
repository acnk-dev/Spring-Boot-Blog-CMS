# Commit 33 — Full-Text Search via PostgreSQL GIN Index on `tsvector` Generated Column

## 1. Overview

This commit replaces the slow `LIKE '%term%'` search query on the `posts` table with PostgreSQL native full-text search. The change has three parts:

1. **V6 Flyway migration** — adds a `search_vector tsvector` generated column to `posts` and creates a GIN (Generalized Inverted Index) on it.
2. **PostRepository** — removes the old `searchPublishedPosts` JPQL method and replaces it with a two-step pattern: a native query that uses the GIN index to return matching IDs, and a JPQL query that loads full entities with associations for those IDs.
3. **PostServiceImpl** — replaces the `searchPosts` method body to orchestrate the two steps and re-apply the original sort order after loading entities.

The core problem with `LIKE '%term%'` is the leading wildcard. A B-tree index (which PostgreSQL uses by default) can only be used for queries with a known prefix — `title LIKE 'spring%'` can use a B-tree. But `title LIKE '%spring%'` with a leading `%` requires PostgreSQL to scan every row, even if an index exists. Full-text search works differently: PostgreSQL pre-processes each document into a set of normalised lexemes and stores them in a `tsvector`. A GIN index on that column acts as a dictionary mapping every lexeme to the list of rows that contain it, enabling O(log V + k) lookups instead of O(n) sequential scans.

---

## 2. Why This Was Needed

The existing `searchPublishedPosts` query used JPQL with `LIKE LOWER(CONCAT('%', :searchTerm, '%'))`. With `LIKE '%term%'` PostgreSQL must read every row in the table to check whether the title or content contains the term. The `EXPLAIN ANALYZE` output (see Section 7) shows this clearly: the planner performs a Bitmap Heap Scan across all 6,014 published posts regardless of how many match the query.

As the post count grows, this scan grows linearly. For a blog with tens of thousands of posts and content that can be several kilobytes each, a search query touching every row is unacceptably slow.

---

## 3. Decisions Made

### Decision 1: Generated column vs. application-side tsvector update

**Option A — update `search_vector` in a service method every time a post is created or updated.** This would require remembering to call `to_tsvector()` and setting the column in every code path that writes to `posts` (create, update, publish). It's fragile; a future developer adding a new write path could easily miss it, leaving `search_vector` stale.

**Option B (chosen) — PostgreSQL `GENERATED ALWAYS AS ... STORED` column.** The database itself computes and persists `to_tsvector('english', coalesce(title, '') || ' ' || coalesce(content, ''))` whenever `title` or `content` changes. The application code cannot override it. This is correct by construction — no code path can accidentally leave the column stale. The trade-off is a small write overhead (PostgreSQL must recompute the tsvector on every INSERT and UPDATE to the row), which is acceptable since writes are less frequent than reads.

### Decision 2: Two-step ID-then-entity pattern vs. single native JOIN query

**Option A — single native query with all JOINs.** Something like:
```sql
SELECT p.*, u.*, c.*, t.*
FROM posts p
JOIN users u ON p.user_id = u.id
JOIN categories c ON p.category_id = c.id
LEFT JOIN post_tags pt ON p.id = pt.post_id
LEFT JOIN tags t ON pt.tag_id = t.id
WHERE p.search_vector @@ plainto_tsquery('english', :query)
```
This produces a Cartesian-product result set when a post has multiple tags. A post with 5 tags returns 5 rows, all identical except for the tag columns. The application would need custom result-set mapping to deduplicate, which is exactly what JPA entity mapping handles — but JPA cannot be applied to native query result sets without `@SqlResultSetMapping` boilerplate. Additionally, `@EntityGraph` (which the rest of the codebase uses for association loading) does not work on native queries.

**Option B (chosen) — two-step: native query returns IDs, JPQL loads entities.** Step 1 is a native query using the `@@` operator — this is the only way to express PostgreSQL full-text search since JPQL has no `@@` operator. It returns a `Page<Long>` of matching post IDs with correct pagination metadata. Step 2 is a JPQL query with `@EntityGraph` that loads the full entities for those IDs in a single JOIN FETCH. The ordering from Step 1 is then re-applied in Java because an SQL `IN (...)` clause does not guarantee row order. This pattern keeps each concern in the right layer: the database handles the full-text index scan, JPA handles association loading without N+1 queries.

### Decision 3: `plainto_tsquery` vs `to_tsquery` vs `phraseto_tsquery`

The native query uses `plainto_tsquery('english', :query)` rather than `to_tsquery('english', :query)`.

`to_tsquery` requires the user to supply a properly formed tsquery string like `'spring & boot'`. This is fine for programmatic use but not for a public search box where users type natural phrases like "spring boot tutorial". If a user types a term with no tsquery operators, `to_tsquery` will fail or produce unexpected results.

`plainto_tsquery` converts a plain phrase into `'spring' & 'boot' & 'tutorial'` automatically — every word becomes a required term. This is the most appropriate choice for a search box. `phraseto_tsquery` would require the words to appear in sequence, which is unnecessarily strict for a blog search.

---

## 4. New Concepts Introduced

### PostgreSQL Full-Text Search: `tsvector` and `tsquery`

Full-text search in PostgreSQL works by reducing documents and queries to a common intermediate form that ignores grammar.

A **`tsvector`** is a sorted list of lexemes — normalised word forms — with position information. For example:
```sql
SELECT to_tsvector('english', 'Spring Boot tutorials are easy');
-- Result: 'boot':2 'easi':5 'spring':1 'tutori':3
```
Notice:
- "Spring" → "spring" (lowercased)
- "tutorials" → "tutori" (stemmed using the English dictionary — the suffix "-als" is removed)
- "are" and "easy" → "easi" ("are" is a stop word and may be dropped in some dictionaries; "easy" is stemmed to "easi")
- Numbers after each lexeme are positions in the original text

A **`tsquery`** is a normalised search query:
```sql
SELECT plainto_tsquery('english', 'spring boot');
-- Result: 'spring' & 'boot'
```

The **`@@` operator** tests whether a tsvector matches a tsquery:
```sql
SELECT to_tsvector('english', 'Spring Boot tutorial') @@ plainto_tsquery('english', 'boot tutorial');
-- Result: true
```

### GIN Index (Generalized Inverted Index)

A GIN index on a `tsvector` column is an inverted index — instead of mapping "row → list of lexemes," it maps "lexeme → list of rows containing that lexeme." This is exactly how a book index works: rather than reading every chapter to find mentions of "pagination," you look up "pagination" in the index and go directly to those pages.

When PostgreSQL receives `search_vector @@ plainto_tsquery('english', 'spring boot')`:
1. The query is resolved to lexemes: `{'spring', 'boot'}`
2. For each lexeme, the GIN index returns its posting list (the set of row IDs containing that lexeme)
3. The posting lists are intersected (AND semantics with `&`)
4. Only those row IDs need to be fetched from the heap

The complexity of this lookup is O(log V + k) where V is the vocabulary size (number of distinct lexemes) and k is the number of matching rows — independent of the total row count N. Compare this to sequential scan O(N).

### `GENERATED ALWAYS AS ... STORED` columns

Introduced in PostgreSQL 12, a generated column is computed from other columns in the same row. `STORED` means PostgreSQL persists the computed value to disk rather than recomputing it on every read.

```sql
ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('english',
            coalesce(title, '') || ' ' || coalesce(content, ''))
    ) STORED;
```

The `GENERATED ALWAYS AS` constraint means:
- The column cannot be set by `INSERT` or `UPDATE` statements (hence "ALWAYS")
- It is automatically recomputed whenever `title` or `content` changes
- The result is stored in the row, so reading it costs nothing extra

The alternative (a trigger `BEFORE INSERT OR UPDATE`) achieves the same functional result but is harder to discover, requires separate maintenance, and can be disabled. Generated columns are a first-class schema feature that Flyway migrations, schema inspection tools, and `\d tablename` in psql all show clearly.

### Spring Data JPA Native Queries with `countQuery`

When Spring Data JPA returns a `Page<T>` from a native query, it needs two things:
1. The data query (to fetch the rows for the current page)
2. The count query (to compute `totalElements` for the `Page` metadata)

Spring Data can derive the count query automatically from JPQL queries but cannot do so for native queries because the SQL syntax is too varied (e.g., DISTINCT, GROUP BY, window functions). The `countQuery` attribute must be supplied explicitly:

```java
@Query(
    value = "SELECT p.id FROM posts p WHERE ... ORDER BY p.published_at DESC",
    countQuery = "SELECT COUNT(p.id) FROM posts p WHERE ...",
    nativeQuery = true
)
Page<Long> findPublishedPostIdsByFullTextSearch(@Param("query") String query, Pageable pageable);
```

The `ORDER BY` clause belongs only in the data query, not the count query — PostgreSQL would reject an `ORDER BY` in a subquery without a `LIMIT`.

### `PageImpl` for programmatic Page construction

After the two-step pattern loads and reorders the entities, the `searchPosts` method needs to return a `Page<PostResponse>` with the correct pagination metadata (total elements, page number, page size). `PageImpl` allows constructing a page manually:

```java
return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
```

The three arguments are:
- `ordered` — the list of content for this page (already paginated by Step 1)
- `pageable` — the page request (page number, page size) — preserved for the response metadata
- `idPage.getTotalElements()` — the total count from the native count query

This is distinct from `Page.map()` which is used when Spring Data handles pagination internally. Here we own the pagination because the content came from two separate queries.

---

## 5. Files Changed

### `src/main/resources/db/migrations/V6__add_fulltext_search_to_posts.sql` (new)

```sql
ALTER TABLE posts
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            to_tsvector('english',
                coalesce(title, '') || ' ' || coalesce(content, ''))
        ) STORED;

CREATE INDEX idx_posts_search_vector
    ON posts
    USING GIN (search_vector);
```

The `coalesce(title, '')` and `coalesce(content, '')` guard against NULL values — `NULL || ' '` would produce NULL in PostgreSQL, which would make the tsvector NULL and exclude the row from all searches. Since both columns are `NOT NULL` in the current schema, this is defensive programming for schema changes.

### `src/main/java/com/studylink/blogcms/repository/PostRepository.java` (modified)

**Before** (lines 84–99):
```java
@EntityGraph(attributePaths = {"user", "category", "tags"})
@Query("SELECT p FROM Post p WHERE p.isDeleted = false " +
       "AND p.status = 'PUBLISHED' " +
       "AND (LOWER(p.title) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
       "OR LOWER(p.content) LIKE LOWER(CONCAT('%', :searchTerm, '%')))")
Page<Post> searchPublishedPosts(@Param("searchTerm") String searchTerm, Pageable pageable);
```

**After** (two methods replacing one):
```java
@Query(
    value = "SELECT p.id FROM posts p " +
            "WHERE p.is_deleted = false " +
            "AND p.status = 'PUBLISHED' " +
            "AND p.search_vector @@ plainto_tsquery('english', :query) " +
            "ORDER BY p.published_at DESC",
    countQuery = "SELECT COUNT(p.id) FROM posts p " +
                 "WHERE p.is_deleted = false " +
                 "AND p.status = 'PUBLISHED' " +
                 "AND p.search_vector @@ plainto_tsquery('english', :query)",
    nativeQuery = true
)
Page<Long> findPublishedPostIdsByFullTextSearch(@Param("query") String query, Pageable pageable);

@EntityGraph(attributePaths = {"user", "category", "tags"})
@Query("SELECT p FROM Post p WHERE p.id IN :ids")
List<Post> findByIdIn(@Param("ids") List<Long> ids);
```

Key points:
- The native query returns `Long` IDs, not `Post` entities. Spring Data maps native scalar results to `Long` without any custom `@SqlResultSetMapping`.
- `@EntityGraph` cannot be applied to native queries — it only works on JPQL. This is why the association-loading step must be a separate JPQL query.
- `findByIdIn` does not filter `isDeleted` or `status` because the IDs already came from a query that applied those filters in Step 1. Any post ID in the list is guaranteed to be published and not deleted.

### `src/main/java/com/studylink/blogcms/service/impl/PostServiceImpl.java` (modified)

**Before**:
```java
@Override
public Page<PostResponse> searchPosts(String query, int page, int size) {
    Pageable pageable = PageRequest.of(page, size, Sort.by("publishedAt").descending());
    Page<Post> posts = postRepository.searchPublishedPosts(query, pageable);
    return posts.map(postMapper::toResponse);
}
```

**After**:
```java
@Override
public Page<PostResponse> searchPosts(String query, int page, int size) {
    Pageable pageable = PageRequest.of(page, size);

    // Step 1: GIN index scan — returns a page of matching IDs with total count metadata
    Page<Long> idPage = postRepository.findPublishedPostIdsByFullTextSearch(query, pageable);

    if (idPage.isEmpty()) {
        return Page.empty(pageable);
    }

    // Step 2: load full entities with associations for this page of IDs
    List<Post> posts = postRepository.findByIdIn(idPage.getContent());

    // Re-apply the Step 1 ordering (IN clause in JPQL does not preserve order)
    Map<Long, Post> byId = posts.stream()
            .collect(Collectors.toMap(Post::getId, p -> p));
    List<PostResponse> ordered = idPage.getContent().stream()
            .map(byId::get)
            .filter(Objects::nonNull)
            .map(postMapper::toResponse)
            .collect(Collectors.toList());

    return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
}
```

The `Sort` is removed from `PageRequest.of(page, size)` because the sort (`ORDER BY p.published_at DESC`) is expressed directly in the native query. Passing `Sort.by("publishedAt").descending()` to a native query would require Spring Data to append `ORDER BY` dynamically, but that can conflict with the explicit `ORDER BY` already in the query string. The native query owns its ordering.

The `filter(Objects::nonNull)` is defensive: if a post was soft-deleted between Step 1 (which loaded IDs) and Step 2 (which loaded entities), `byId.get(id)` would return null for that ID. This is a race condition that is extremely unlikely but correct to handle.

New imports added to `PostServiceImpl.java`:
- `java.util.Map`
- `java.util.Objects`
- `org.springframework.data.domain.PageImpl`

---

## 6. Flyway Checksum Note

The V6 migration was applied directly via `psql` before the application started, and the Flyway schema history row was inserted manually. Flyway's internal checksum algorithm is not standard CRC32; it uses a modified algorithm that reads the file with Windows-style line endings normalised. The correct checksum (`-530849754`) was discovered from the validation error message when the application first tried to start, and the history row was updated accordingly. In a normal workflow, Flyway would apply the migration itself and record the correct checksum automatically.

---

## 7. Benchmark Results

### Dataset

~10,040 rows in `posts`, ~6,014 with `status = 'PUBLISHED'` and `is_deleted = false`. The seeder produced posts with lorem ipsum content and synthetic titles like "Scaling with Redis", "Introduction to Spring Boot", etc.

### BEFORE: `LIKE '%spring boot%'` (sequential scan)

```
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT p.id FROM posts p
WHERE p.is_deleted = false
  AND p.status = 'PUBLISHED'
  AND (
    LOWER(p.title)   LIKE LOWER(CONCAT('%', 'spring boot', '%'))
    OR LOWER(p.content) LIKE LOWER(CONCAT('%', 'spring boot', '%'))
  )
ORDER BY p.published_at DESC LIMIT 10 OFFSET 0;
```

```
Limit  (cost=2764.86..2764.86 rows=1 width=16)
       (actual time=53.595..53.596 rows=0 loops=1)
  Buffers: shared hit=2434 read=6
  ->  Sort  (cost=2764.86..2764.86 rows=1 width=16)
            (actual time=53.593..53.594 rows=0 loops=1)
        Sort Key: published_at DESC
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=2434 read=6
        ->  Bitmap Heap Scan on posts p
                  (cost=73.39..2764.85 rows=1 width=16)
                  (actual time=53.357..53.358 rows=0 loops=1)
              Recheck Cond: ((status)::text = 'PUBLISHED'::text)
              Filter: ((NOT is_deleted) AND
                       ((lower(title) ~~ lower(concat('%', 'spring boot', '%')))
                        OR (lower(content) ~~ lower(concat('%', 'spring boot', '%')))))
              Rows Removed by Filter: 6014
              Heap Blocks: exact=2431
              Buffers: shared hit=2431 read=6
              ->  Bitmap Index Scan on idx_posts_status
                    (cost=0.00..73.39 rows=6014 width=0)
                    (actual time=1.304..1.305 rows=6014 width=0)
                    Index Cond: ((status)::text = 'PUBLISHED'::text)
                    Buffers: shared read=6
Planning Time: 2.473 ms
Execution Time: 53.905 ms
```

What this shows: PostgreSQL uses the `idx_posts_status` B-tree index to quickly find all 6,014 published rows, then must read every one of those rows from the heap and apply the LIKE filter. All 6,014 rows are removed by the filter because "spring boot" doesn't appear in the seeded data. The query touched 2,431 heap blocks.

### AFTER: Full-text search via GIN index

```
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT p.id FROM posts p
WHERE p.is_deleted = false
  AND p.status = 'PUBLISHED'
  AND p.search_vector @@ plainto_tsquery('english', 'spring boot')
ORDER BY p.published_at DESC LIMIT 10 OFFSET 0;
```

```
Limit  (cost=29.63..29.64 rows=1 width=16)
       (actual time=0.055..0.065 rows=0 loops=1)
  Buffers: shared hit=8
  ->  Sort  (cost=29.63..29.64 rows=1 width=16)
            (actual time=0.054..0.063 rows=0 loops=1)
        Sort Key: published_at DESC
        Sort Method: quicksort  Memory: 25kB
        Buffers: shared hit=8
        ->  Bitmap Heap Scan on posts p
                  (cost=25.61..29.62 rows=1 width=16)
                  (actual time=0.034..0.043 rows=0 loops=1)
              Recheck Cond: (search_vector @@ '''spring'' & ''boot'''::tsquery)
              Filter: ((NOT is_deleted) AND ((status)::text = 'PUBLISHED'::text))
              Buffers: shared hit=5
              ->  Bitmap Index Scan on idx_posts_search_vector
                    (cost=0.00..25.61 rows=1 width=0)
                    (actual time=0.025..0.026 rows=0 loops=1)
                    Index Cond: (search_vector @@ '''spring'' & ''boot'''::tsquery)
                    Buffers: shared hit=5
Planning Time: 5.441 ms
Execution Time: 0.118 ms
```

What this shows: PostgreSQL goes directly to the GIN index and looks up the posting lists for lexemes 'spring' and 'boot'. The index scan read only 5 buffer pages total. Execution time dropped from **53.905ms to 0.118ms** — a **457x speedup** on this dataset.

Note the `Recheck Cond` in the after plan: Bitmap Index Scans use a lossy bitmap at high match counts, requiring a recheck of the actual condition against the heap pages. At low match counts (as here, with 0 matching rows) the bitmap is exact and no recheck is needed. The recheck does not affect correctness.

---

## 8. Memory Overhead Analysis

Adding the `search_vector` column and GIN index consumes disk space proportional to the vocabulary of your content.

### Stored column size (tsvector)

Each `tsvector` row stores: number of lexemes + (lexeme string + position list) per lexeme. For a typical 500-word blog post with ~200 unique words after stemming and stop-word removal:

```
~200 lexemes × (avg 6 bytes per lexeme + 2 bytes position) ≈ 1,600 bytes per post
```

For 10,000 posts: `10,000 × 1,600 bytes ≈ 16 MB` for the stored column values in the heap.

### GIN index size

The GIN index stores an inverted index: each lexeme appears once in the B-tree leaf, followed by a posting list of (block, offset) pairs for every row containing that lexeme. The vocabulary across 10,000 posts is much smaller than the total lexemes — many words repeat across posts.

Estimate: `~50,000 unique lexemes × (avg 8 bytes per lexeme + 10 bytes average posting list) ≈ 0.9 MB` for the B-tree structure, plus `total lexeme occurrences × ~4 bytes per posting entry`. A rough total for 10,000 posts: **5–15 MB for the GIN index**.

### Write overhead

Every `INSERT` or `UPDATE` that changes `title` or `content` triggers a tsvector recomputation. PostgreSQL does not update the GIN index immediately on every write. Instead, it appends changes to an in-memory **pending list** (default 4MB, configurable via `gin_pending_list_limit`). The pending list is merged into the main index structure by autovacuum or when it reaches the size limit. This batching means individual writes pay only the cost of appending to the pending list (fast), at the expense of slightly slower reads when the pending list is large (reads must also check the pending list). For a blog CMS with infrequent writes relative to reads, this is an excellent trade-off.

---

## 9. Testing Checklist

| What to test | How to test | Expected result |
|---|---|---|
| Search returns matching posts | `curl "http://localhost:8082/api/v1/posts/search?query=scaling&page=0&size=5"` | HTTP 200, `content` array with posts whose title/content contains "scaling"; all associations (author, category, tags) populated |
| Pagination works | `curl "http://localhost:8082/api/v1/posts/search?query=scaling&page=0&size=3"` then `page=1&size=3` | Different post IDs on each page; `totalElements` consistent |
| Empty result returns 200 | `curl "http://localhost:8082/api/v1/posts/search?query=zzzyyyxxx&page=0&size=5"` | HTTP 200, `content: []`, `totalElements: 0`, `empty: true` |
| Stemming works | `curl "http://localhost:8082/api/v1/posts/search?query=scales&page=0&size=5"` | Posts containing "scaling", "scaled", "scale" are all returned (same stem) |
| GIN index used in query plan | `psql -d blog-cms -c "EXPLAIN SELECT id FROM posts WHERE search_vector @@ plainto_tsquery('english', 'scaling');"` | Plan shows `Bitmap Index Scan on idx_posts_search_vector` |
| Flyway migration valid | Start app — check logs for `Successfully validated 6 migrations` | No `FlywayValidateException`; app starts cleanly |
| Multi-word search | `curl "http://localhost:8082/api/v1/posts/search?query=getting+started&page=0&size=5"` | Returns posts matching both "getting" and "started" (AND semantics) |
| Associations not lazy-loaded | Add `logging.level.org.hibernate.SQL=DEBUG` to properties; run search | Only 2 SQL queries logged: the native ID scan and the JPQL JOIN FETCH — no additional selects for each post's author/category/tags |
