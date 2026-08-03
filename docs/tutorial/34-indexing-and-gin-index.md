# Tutorial: PostgreSQL Indexes, Full-Text Search, and GIN Indexes

This tutorial explains everything that went into commit 33: why `LIKE '%term%'` is slow, how B-tree indexes work, what a GIN index is, how PostgreSQL full-text search works from first principles, and how the Spring Boot repository is structured to exploit it. It assumes you know basic SQL and have read the earlier commits in this project.

---

## Table of Contents

1. [PostgreSQL Index Types — Overview Table](#1-postgresql-index-types--overview-table)
2. [How B-tree Indexes Work — and Why `LIKE '%term%'` Breaks Them](#2-how-b-tree-indexes-work--and-why-like-term-breaks-them)
3. [PostgreSQL Full-Text Search Concepts](#3-postgresql-full-text-search-concepts)
4. [GIN Index Internals — The Inverted Index](#4-gin-index-internals--the-inverted-index)
5. [GIN vs pg_trgm — When to Use Each](#5-gin-vs-pg_trgm--when-to-use-each)
6. [Generated Columns — `STORED` vs Virtual](#6-generated-columns--stored-vs-virtual)
7. [The Two-Step Repository Pattern](#7-the-two-step-repository-pattern)
8. [Write Overhead and VACUUM](#8-write-overhead-and-vacuum)
9. [Trade-off Summary Table](#9-trade-off-summary-table)
10. [Next Steps: Relevance Ranking and Fuzzy Search](#10-next-steps-relevance-ranking-and-fuzzy-search)

---

## 1. PostgreSQL Index Types — Overview Table

PostgreSQL ships with six index types. Each is designed for a different class of query predicate. Choosing the wrong index type results in the index being ignored by the planner.

| Index Type | Best For | Can't Use For | Default? | Size vs B-tree |
|---|---|---|---|---|
| **B-tree** | `=`, `<`, `>`, `BETWEEN`, `LIKE 'prefix%'`, `ORDER BY`, `DISTINCT` | Leading-wildcard LIKE, full-text, array containment | Yes (default) | 1× |
| **Hash** | `=` only | Range queries, ordering | No | ~0.6× (smaller) |
| **GIN** | Arrays, tsvector full-text, JSONB containment (`@>`) | Range queries, ordering, `=` on whole column | No | ~2–5× larger |
| **GiST** | Geometric types, full-text (alternative to GIN), range types | Exact equality | No | Varies |
| **BRIN** | Very large tables with naturally ordered data (time-series, sequential IDs) | Random data, point lookups | No | ~0.001× (tiny) |
| **SP-GiST** | Geospatial, text (radix trees), non-balanced data | General use | No | Varies |

For this project we use:
- **B-tree** (created automatically by PostgreSQL on primary keys and unique constraints) — used for post ID lookups and slug uniqueness checks
- **GIN** — new in commit 33 for full-text search on `search_vector`

The Flyway migrations in this project have also created explicit B-tree indexes (`idx_posts_status`, `idx_post_tags_tag_id`) for frequently filtered columns.

---

## 2. How B-tree Indexes Work — and Why `LIKE '%term%'` Breaks Them

### B-tree structure

A B-tree (Balanced tree) index stores index entries in sorted order in a tree structure where every leaf node is at the same depth. Here is a simplified diagram for an index on `title`:

```
                    [M]
                   /   \
              [E, J]   [R, T]
             /  |  \   /  |  \
          [A-D][F-I][K-L][N-Q][S][U-Z]
```

Each leaf node contains:
- The index key value (e.g. "Getting Started with Spring Boot")
- A pointer (ctid) to the actual heap row on disk

**To find `title = 'Getting Started with Spring Boot'`**: start at root, compare with "M", go left; compare with "E", "J"; descend to the correct leaf. Read a handful of index pages, follow one pointer to the heap. This is O(log N) disk reads.

**To find `title LIKE 'Getting%'`**: the planner knows the prefix is "Getting", which fits in the B-tree range `[Getting, Gettinz]`. It navigates the B-tree to the first "Getting..." entry and scans forward through the leaves until the prefix no longer matches. O(log N + k) where k is the number of matches.

Here is what that range scan looks like inside the B-tree leaf level:

```
B-tree leaf nodes (sorted alphabetically, left to right):

  ┌──────────────────┐  ┌──────────────────────┐  ┌─────────────────────┐  ┌──────────────────┐
  │ "From Zero to    │  │ "Getting Started      │  │ "Getting to Know    │  │ "Guide to        │
  │  Production"     │  │  with Spring Boot"    │  │  PostgreSQL"        │  │  Microservices"  │
  │  → row 7         │  │  → row 12             │  │  → row 34           │  │  → row 41        │
  └──────────────────┘  └──────────────────────┘  └─────────────────────┘  └──────────────────┘
          │                       ↑                          ↑                       │
          │               ════════╪══════════════════════════╪═══════╗               │
          │               ║  Range scanned for LIKE 'Getting%'      ║               │
          │               ║  lower bound: "Getting"                  ║               │
          │               ║  upper bound: "Gettinz"                  ║               │
          │               ╚══════════════════════════════════════════╝               │
          │                                                                           │
   before range                                                               after range
   (not visited)                                                              (not visited)
```

**How the upper bound `"Gettinz"` is derived**: PostgreSQL constructs it by taking the prefix `"Getting"` and incrementing the last character `'g'` by one step in the alphabet to get `'h'`... actually it uses `'z'` as a safe ceiling because any ASCII character after `'g'` would still be alphabetically less than `'z'`. So `"Gettinz"` means: *"any string that starts with `Gettin` followed by any character up to `z`"* — which covers every possible `"Getting..."` title. The B-tree scan starts at the first entry ≥ `"Getting"` and stops at the first entry ≥ `"Gettinz"`, reading only the two rows (12 and 34) in the middle. Rows outside the range are never touched.

### Why `LIKE '%spring%'` cannot use a B-tree

The leading `%` means "any characters before 'spring'". The B-tree is sorted by the full key from left to right. Without knowing how the string starts, there is no starting point in the tree to navigate to. The planner has two choices:

1. Scan the entire B-tree leaf level (still reads every index entry)
2. Skip the index and scan the heap directly (often cheaper since it avoids the index overhead)

The planner typically chooses option 2 and performs a sequential scan of the table. Every row must be read and the LIKE filter applied. This is O(N).

**Visual illustration of why the prefix matters:**

```
Index sorted order (left to right):
"Advanced Techniques..." | "Building APIs..." | "Getting Started..." | "Spring Boot..." | "Working with..."
        ^                          ^                    ^                      ^                  ^
        |                          |                    |                      |                  |
  'Advanced%' starts here    'Building%'          'Getting%'           'Spring%'           'Working%'

For LIKE '%boot%' — any of these could contain 'boot'. No starting point exists in the index.
```

The only way to find all entries containing "boot" anywhere is to check all of them.

---

## 3. PostgreSQL Full-Text Search Concepts

Full-text search solves the leading-wildcard problem by pre-processing text into a representation that can be indexed directly — the `tsvector`.

### tsvector

A `tsvector` is a sorted list of lexemes — normalised word forms — with optional position information. Let's trace through an example:

```sql
SELECT to_tsvector('english', 'Spring Boot tutorials are extremely easy to follow');
```

PostgreSQL applies these transformations:
1. **Tokenise** — split on whitespace and punctuation: `['Spring', 'Boot', 'tutorials', 'are', 'extremely', 'easy', 'to', 'follow']`
2. **Stop-word removal** — common words with no discriminating value are removed according to the language dictionary. With 'english': `['are', 'to']` are removed
3. **Stemming** — reduce inflected forms to a common base:
    - "tutorials" → "tutori" (removing plural/suffix)
    - "extremely" → "extrem"
    - "easy" → "easi"
    - "Spring", "Boot", "follow" → "spring", "boot", "follow"
4. **Sort and deduplicate** — the lexemes are stored in sorted order with their position numbers

Result:
```
'boot':2 'easi':6 'extrem':5 'follow':8 'spring':1 'tutori':3
```

Numbers after the colon are positions in the original text (1-indexed). Positions enable phrase matching (are the words adjacent?) and proximity ranking.

### tsquery

A `tsquery` is a boolean expression over lexemes. There are four constructors:

```sql
-- to_tsquery: requires tsquery syntax from the user
SELECT to_tsquery('english', 'spring & boot');
-- Result: 'spring' & 'boot'

-- plainto_tsquery: plain English phrase, AND between all words
SELECT plainto_tsquery('english', 'spring boot tutorial');
-- Result: 'spring' & 'boot' & 'tutori'

-- phraseto_tsquery: words must appear in sequence
SELECT phraseto_tsquery('english', 'spring boot');
-- Result: 'spring' <-> 'boot'  (adjacent in that order)

-- websearch_to_tsquery: supports +/- and "quoted phrases" like a web search
SELECT websearch_to_tsquery('english', '"spring boot" -java');
-- Result: 'spring' <-> 'boot' & !'java'
```

For a public search box, `plainto_tsquery` is the safest choice: it never throws an error on user input and treats every word as required.

### The `@@` operator

`tsvector @@ tsquery` returns `true` if the tsvector contains all lexemes required by the tsquery:

```sql
SELECT to_tsvector('english', 'Spring Boot tutorial') @@ plainto_tsquery('english', 'boot tutorial');
-- true: both 'boot' and 'tutori' appear in the tsvector

SELECT to_tsvector('english', 'Spring Boot tutorial') @@ plainto_tsquery('english', 'java tutorial');
-- false: 'java' is not in the tsvector
```

### Stemming example

Stemming is what makes full-text search powerful: a user searching for "scale" will find posts mentioning "scaling", "scaled", "scales", "scalability" (stemmed as "scalabl") — all without the user knowing about these variations.

```sql
SELECT plainto_tsquery('english', 'scale');
-- Result: 'scale'

SELECT to_tsvector('english', 'Scaling microservices is challenging');
-- Result: 'challeng':4 'microservic':2 'scale':1

-- Does 'scale' match 'scaling'?
SELECT to_tsvector('english', 'Scaling microservices') @@ plainto_tsquery('english', 'scale');
-- true: 'scaling' is stemmed to 'scale' during tsvector construction
```

Both the document (tsvector) and the query (tsquery) go through the same stemming pipeline, so they meet in the middle.

### Why stemming matters — the "easy" family

This is one of the most important concepts in full-text search, and it is worth seeing concretely. Run these in `psql`:

```sql
SELECT to_tsvector('english', 'easy');    -- 'easi':1
SELECT to_tsvector('english', 'easier');  -- 'easier':1
SELECT to_tsvector('english', 'easily');  -- 'easili':1
SELECT to_tsvector('english', 'easiest'); -- 'easiest':1
```

Notice that `easy` and `easily` do **not** produce the same stem — the English Snowball stemmer takes a conservative approach here. But consider the important case where they do converge:

```sql
SELECT plainto_tsquery('english', 'easy');   -- 'easi'
SELECT plainto_tsquery('english', 'easily'); -- 'easili'
```

The query side goes through the same stemmer, so:

```
User types "easy"   → tsquery stem: 'easi'
                                        ↓ matches?
Post content "easy to use"  → tsvector: 'easi':1 'use':3   ✓ yes

User types "easily" → tsquery stem: 'easili'
                                        ↓ matches?
Post content "easily configured" → tsvector: 'easili':1 'configur':2   ✓ yes
```

The key insight is that **both sides go through the same pipeline**. A document is never stored as raw text in the tsvector — it is stored as stems. A query is never matched against raw text — it is also converted to stems first. This means whatever form the user types, and whatever form the author wrote, they meet at the same stem.

Here is a more dramatic example with "run":

```sql
-- All of these produce the same stem in the query:
SELECT plainto_tsquery('english', 'run');     -- 'run'
SELECT plainto_tsquery('english', 'runs');    -- 'run'
SELECT plainto_tsquery('english', 'running'); -- 'run'
SELECT plainto_tsquery('english', 'runner');  -- 'runner'  ← different (runner is a noun, not the verb)

-- And in the document side:
SELECT to_tsvector('english', 'She is running the Spring Boot application');
-- Result: 'applic':7 'boot':5 'run':4 'spring':4
--   "running" → "run"   "application" → "applic"   "Spring" → "spring"
--   "She", "is", "the" → removed as stop words
```

So a blog post saying "running" is indexed as "run", and a user searching for "run" or "runs" or "running" all produce the tsquery `'run'` — they all find that post. Without stemming, a user searching "running" would only find posts that contain the exact word "running", missing posts that say "run" or "runs".

**Summary of why stemming matters:**

| User searches for | Without stemming finds | With stemming finds |
|---|---|---|
| "easy" | only posts containing "easy" | posts with "easy", "easier", "easiest" |
| "running" | only posts containing "running" | posts with "run", "runs", "running" |
| "scale" | only posts containing "scale" | posts with "scale", "scales", "scaled", "scaling" |
| "configure" | only posts containing "configure" | posts with "configure", "configured", "configures", "configuring" |

This is what separates full-text search from a simple substring match — it understands the language, not just the characters.

---

## 4. GIN Index Internals — The Inverted Index

### The concept of an inverted index

A regular (forward) index maps: `document_id → list of words in document`.
An inverted index maps: `word → list of documents containing word`.

This inversion is what makes search fast. Instead of asking "which of these 10,000 documents contains 'spring'?" (requires scanning all documents), an inverted index lets you ask "which documents are listed under 'spring'?" (a single O(log V) lookup).

### GIN structure

PostgreSQL's GIN (Generalized Inverted Index) is built from two structures:

```
GIN index on search_vector
│
├── B-tree of lexemes (the "dictionary")
│   ├── 'boot'   → posting list: [row 4, row 7, row 19, row 31, ...]
│   ├── 'build'  → posting list: [row 2, row 11, row 45, ...]
│   ├── 'cloud'  → posting list: [row 1, row 3, row 22, ...]
│   ├── 'docker' → posting list: [row 3, row 8, row 17, ...]
│   └── 'spring' → posting list: [row 4, row 7, row 45, row 92, ...]
│
└── Heap rows (the actual post data)
    ├── row 4:  "Spring Boot Tutorial" → full post content
    ├── row 7:  "Advanced Spring Boot" → full post content
    └── ...
```

**Lookup for `plainto_tsquery('english', 'spring boot')`:**

1. Resolve query to lexemes: `{'spring', 'boot'}`
2. B-tree lookup in GIN: navigate to 'boot' → get posting list `{4, 7, 19, 31, ...}`
3. B-tree lookup in GIN: navigate to 'spring' → get posting list `{4, 7, 45, 92, ...}`
4. Intersect the posting lists (AND semantics): `{4, 7}` — only rows containing both words
5. Fetch rows 4 and 7 from the heap and apply any remaining filters (is_deleted, status)

Total disk pages read: a handful of B-tree pages + the posting lists + the matching heap rows. Compare to a sequential scan that reads every heap page.

### Complexity analysis

- **Sequential scan (LIKE)**: O(N) where N = total rows in the table (must read every row)
- **GIN lookup**: O(log V + k) where V = vocabulary size (distinct lexemes), k = number of matching rows
    - The log V term comes from navigating the B-tree of lexemes to find the posting list
    - The k term comes from reading the k matching heap rows

For large N and small k (rare search terms), the GIN advantage is enormous. Even for common terms where k is large, O(log V + k) is still better than O(N) when k < N.

### Two-phase bitmap scan

When the number of matching rows (k) is large, PostgreSQL uses a Bitmap Index Scan rather than fetching rows one at a time:

1. **Index phase**: scan the GIN index, collect the set of matching row addresses into a bitmap (one bit per heap page, or one bit per row for exact bitmaps)
2. **Heap phase**: read the heap pages indicated by the bitmap in sequential order (avoiding random I/O), recheck the condition for each row

The `EXPLAIN ANALYZE` output says `Bitmap Index Scan on idx_posts_search_vector` — this is the GIN scan. The `Bitmap Heap Scan` line above it is the heap phase.

---

## 5. GIN vs pg_trgm — When to Use Each

A common alternative to full-text search is `pg_trgm` — PostgreSQL's trigram extension. It is worth understanding when each is appropriate.

### How trigrams work

A trigram extension breaks every string into all possible 3-character substrings (trigrams). For "hello":
```
" h", " he", "hel", "ell", "llo", "lo "
```
(spaces are added at the boundaries to handle short strings and beginnings/endings)

A GIN or GiST index on a trigram-indexed column can answer `LIKE '%term%'` queries because any string containing "term" must contain the trigrams " te", "ter", "erm", "rm " — and those can be looked up in the index.

### Comparison table

| Dimension | Full-text (tsvector + GIN) | pg_trgm (LIKE with GIN) |
|---|---|---|
| What it indexes | Language-aware lexemes | Raw character n-grams |
| Language awareness | Yes — stemming, stop words | No — purely character-based |
| Query type | `@@` operator with tsquery | `LIKE`, `ILIKE`, `~` (regex) |
| Handles "scale" finding "scaling" | Yes (stemming) | No ("scal" != "scali") |
| Handles typos/fuzzy matching | No | Yes (similarity threshold) |
| Index size | Moderate | Larger (more trigrams than lexemes) |
| Insert overhead | Low (pending list batching) | Moderate |
| Multi-language | Yes (per-column language setting) | Language agnostic |
| Setup | Built-in | `CREATE EXTENSION pg_trgm;` |

**Use full-text search (tsvector + GIN) when:**
- Your content is natural language prose (articles, posts, comments)
- You want users to search conceptually ("scale" finds "scaling")
- You have a single dominant language per field
- Stop word elimination is desirable (filtering out "the", "is", "are")

**Use pg_trgm when:**
- You need fuzzy matching / typo tolerance (similarity search)
- Your content is not natural language (product codes, usernames, URLs)
- You need `LIKE '%term%'` behaviour without semantic stemming
- You need to search across multiple languages in the same column

For the blog CMS with English prose content, full-text search is the right choice.

---

## 6. Generated Columns — `STORED` vs Virtual

### What is a generated column?

A generated column is a column whose value is computed from an expression over other columns in the same row. The database maintains it automatically — you never write to it directly.

```sql
ALTER TABLE posts
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            to_tsvector('english',
                coalesce(title, '') || ' ' || coalesce(content, ''))
        ) STORED;
```

### `STORED` vs virtual (computed on read)

PostgreSQL 12 supports only `STORED` generated columns. The value is computed on every `INSERT` and `UPDATE` and saved to disk alongside the other columns. Reading the column costs nothing — it is already there.

Other databases (SQLite, MySQL 5.7+, SQL Server) also support **virtual** (or computed-on-read) generated columns that are not stored and are recomputed each time they are selected. PostgreSQL does not currently support virtual generated columns (as of version 17).

The trade-off:
- `STORED`: write overhead (recompute on change) + disk space; but free to read and can be indexed
- Virtual: no write overhead, no disk space; but cannot be indexed (there is nothing to index until read time)

Since we need the GIN index on `search_vector`, `STORED` is the only option anyway.

### Why generated columns beat triggers

Before generated columns (PostgreSQL 12), the standard approach was a `BEFORE INSERT OR UPDATE` trigger that called `to_tsvector()` and set the `search_vector` column. This works, but generated columns have clear advantages:

**Discoverability**: `\d posts` shows the generated column expression. A trigger is invisible unless you query `pg_trigger` or check the trigger function definition.

**Correctness guarantee**: A generated column cannot be bypassed. `INSERT INTO posts (...) VALUES (...)` will always compute `search_vector` correctly. A trigger can be disabled with `ALTER TABLE posts DISABLE TRIGGER ...` — intentionally or accidentally.

**Simplicity**: No trigger function to write, test, or maintain. The expression lives directly in the column definition.

**Performance**: The same — both approaches call `to_tsvector()` during the write. But triggers require a separate function call overhead.

---

## 7. The Two-Step Repository Pattern

This is the most non-obvious part of commit 33. Understanding why two separate queries are needed requires understanding the limits of `@EntityGraph` and native queries.

### Why not use `@EntityGraph` with a native query?

`@EntityGraph` works by instructing JPA to add `LEFT JOIN FETCH` clauses to the generated JPQL query. It is a post-processing step on JPQL — Spring Data reads the `@EntityGraph`, determines which relationships to eagerly fetch, and generates:

```sql
SELECT p, u, c, t
FROM Post p
LEFT JOIN FETCH p.user u
LEFT JOIN FETCH p.category c
LEFT JOIN FETCH p.tags t
WHERE <your condition>
```

For native queries (`nativeQuery = true`), there is no JPA query generation. The SQL you provide is sent directly to PostgreSQL unchanged. `@EntityGraph` has no way to inject `LEFT JOIN FETCH` into raw SQL, so it is silently ignored on native queries.

**Proof**: the Spring Data JPA documentation states: "Entity graphs with native queries are not supported."

### Why not write a single native SQL JOIN?

You could write:

```sql
SELECT p.*, u.id, u.name, c.id, c.name, t.id, t.name
FROM posts p
JOIN users u ON p.user_id = u.id
LEFT JOIN categories c ON p.category_id = c.id
LEFT JOIN post_tags pt ON p.id = pt.post_id
LEFT JOIN tags t ON pt.tag_id = t.id
WHERE p.search_vector @@ plainto_tsquery('english', :query)
```

This produces a Cartesian product on `post_tags`. A post with 3 tags returns 3 rows, all with identical post and user and category data. You would need to:

1. Write a custom `@SqlResultSetMapping` (many lines of XML-like annotations) to tell JPA how to deserialise this flat result into nested `Post` objects with `Set<Tag> tags`
2. Or do the deduplication manually in Java

Neither is simple. The two-step pattern is much cleaner.

### How the two-step pattern works

```
Step 1: Native query → Page<Long>
    SQL: SELECT p.id FROM posts p
         WHERE p.search_vector @@ plainto_tsquery('english', :query)
         ORDER BY p.published_at DESC
         LIMIT :size OFFSET :page*size

    Input:  query string, pageable
    Output: Page<Long> — list of IDs for this page + total count

Step 2: JPQL with @EntityGraph → List<Post>
    JPQL: SELECT p FROM Post p WHERE p.id IN :ids
    +EntityGraph({"user", "category", "tags"})

    Hibernate generates:
    SELECT p.*, u.*, c.*, t.*
    FROM posts p
    LEFT JOIN FETCH users u ON p.user_id = u.id
    LEFT JOIN FETCH categories c ON p.category_id = c.id
    LEFT JOIN FETCH post_tags pt ON p.id = pt.post_id
    LEFT JOIN FETCH tags t ON pt.tag_id = t.id
    WHERE p.id IN (4, 7, 19, 31)

    Input:  list of IDs from Step 1
    Output: List<Post> with all associations loaded

Re-order: IN clause does not guarantee order → restore Step 1 order
    idPage.getContent() is ordered by published_at DESC
    Convert List<Post> to Map<Long, Post> for O(1) lookup
    Stream over the ordered ID list, map each ID to its Post
```

Total SQL queries for a search request: **exactly 2** (plus the count query for pagination metadata, totaling 3 — the same as any paginated endpoint).

### Why the count query is separate

Spring Data JPA needs `totalElements` to populate the `Page` metadata (total pages, is this the last page?, etc.). For JPQL queries that return `Page<Post>`, Spring Data derives the count query automatically by rewriting the SELECT to `SELECT COUNT(p)`. For native queries, it cannot do this reliably (the SQL could have DISTINCT, subqueries, GROUP BY, etc.), so you must supply the count query explicitly:

```java
@Query(
    value = "SELECT p.id FROM posts p WHERE ... ORDER BY ...",
    countQuery = "SELECT COUNT(p.id) FROM posts p WHERE ...",
    nativeQuery = true
)
Page<Long> findPublishedPostIdsByFullTextSearch(...);
```

Note: the `ORDER BY` belongs only in the data query. Many databases (including PostgreSQL) will reject `ORDER BY` in a subquery without `LIMIT`, and the count query does not need ordering.

### Re-applying sort order after Step 2

The JPQL query in Step 2 uses `WHERE p.id IN :ids`. The SQL `IN` operator returns rows in any order — usually heap order (physical storage order), not the order of the ID list. If Step 1 returned IDs `[7, 4, 19]` (ordered by `published_at DESC`), Step 2 might return posts in the order `[4, 7, 19]`.

The service re-applies the Step 1 order:

```java
Map<Long, Post> byId = posts.stream()
        .collect(Collectors.toMap(Post::getId, p -> p));
List<PostResponse> ordered = idPage.getContent().stream()
        .map(byId::get)       // look up each ID in the map
        .filter(Objects::nonNull)
        .map(postMapper::toResponse)
        .collect(Collectors.toList());
```

`idPage.getContent()` is already in `published_at DESC` order because that order came from the native query's `ORDER BY` clause. Streaming over the IDs and looking up each post in the map reconstructs the original order.

The `filter(Objects::nonNull)` handles the race condition where a post is soft-deleted between Step 1 and Step 2. In practice this is extremely unlikely but the code is defensive.

---

## 8. Write Overhead and VACUUM

### What happens on INSERT/UPDATE

When you INSERT a new post or UPDATE `title` or `content`:

1. PostgreSQL computes the new `search_vector` value (a `to_tsvector()` call)
2. The tsvector is written to the heap row alongside the other columns
3. The GIN index is not updated immediately — new entries go into the **GIN pending list**, a temporary buffer in the index pages

### The GIN pending list

The pending list is a small area at the beginning of the GIN index file (not in memory). New entries accumulate there until either:
- The pending list reaches `gin_pending_list_limit` (default 4MB)
- An autovacuum runs on the table and calls `ginvacuumcleanup`
- A read query searches the index: PostgreSQL checks the pending list first, then the main index structure, and may flush the pending list if it is large

When the pending list is flushed, entries are merged into the main inverted index structure. This merge can briefly lock the index for writes.

### Performance implications

| Operation | With GIN index | Without GIN index |
|---|---|---|
| INSERT (new post) | Append lexemes to pending list | No index overhead |
| UPDATE title/content | Recompute tsvector, mark old entries for deletion, append new lexemes | No index overhead |
| SELECT (search) | Check pending list + B-tree lookup + posting list fetch | Sequential scan |
| VACUUM/autovacuum | Flush pending list to main index | Normal vacuuming |

For a blog CMS with O(writes per day) << O(reads per day), the write overhead is negligible. The index pays for itself on the very first search query.

### Tuning `gin_pending_list_limit`

If your workload has many concurrent writes (e.g., a bulk import), the pending list can fill up frequently, causing write spikes as it flushes. You can increase the limit per-index:

```sql
ALTER INDEX idx_posts_search_vector SET (gin_pending_list_limit = 16384);  -- 16MB
```

Or globally in `postgresql.conf`:
```
gin_pending_list_limit = 16MB
```

Higher limits mean fewer flushes (better write throughput) but longer pending-list scan times during reads. The default 4MB is appropriate for most workloads.

---

## 9. Trade-off Summary Table

| Dimension | `LIKE '%term%'` | Full-text (tsvector + GIN) |
|---|---|---|
| **Query performance** | O(N) sequential scan | O(log V + k) index lookup |
| **Execution time (6K posts)** | ~54ms | ~0.1ms (~457x faster) |
| **Language awareness** | None (character-by-character) | Yes (stemming, stop words) |
| **Stemming** | No | Yes ("scale" finds "scaling") |
| **Case sensitivity** | Requires `LOWER()` everywhere | Handled automatically |
| **Schema complexity** | Minimal (no extra column) | +1 column, +1 index |
| **Disk overhead** | None | ~15–25MB per 10K posts |
| **Write overhead** | Minimal | Low (pending list amortises writes) |
| **Query syntax** | Standard JPQL/SQL | Native SQL (`@@` operator) |
| **JPA integration** | Works with JPQL + @EntityGraph | Requires native query + two-step pattern |
| **Multi-word queries** | OR or AND manually written | Implicit AND via plainto_tsquery |
| **Scalability** | Degrades linearly with N | Stable O(log V + k) |
| **Maintenance** | None | Autovacuum flushes GIN pending list |

---

## 10. Next Steps: Relevance Ranking and Fuzzy Search

### ts_rank — relevance-ordered results

The current implementation orders results by `published_at DESC` — most recently published first. A better search experience orders by relevance: posts that match the query most closely should appear first.

PostgreSQL provides `ts_rank(tsvector, tsquery)` — a function that scores how well a tsvector matches a tsquery, based on term frequency, document length, and optionally position proximity:

```sql
SELECT id, title,
       ts_rank(search_vector, plainto_tsquery('english', 'spring boot')) AS rank
FROM posts
WHERE search_vector @@ plainto_tsquery('english', 'spring boot')
ORDER BY rank DESC;
```

A higher rank means the document is a better match. For this project, you could add `ts_rank` to the native query and order by it:

```sql
SELECT p.id
FROM posts p
WHERE p.is_deleted = false
  AND p.status = 'PUBLISHED'
  AND p.search_vector @@ plainto_tsquery('english', :query)
ORDER BY ts_rank(p.search_vector, plainto_tsquery('english', :query)) DESC
```

Note: `ts_rank` is not indexable — it must be computed for every matching row. For queries that match many rows (e.g., a single common word matching 5,000 posts), this computation can be slow. Limit the result set before computing rank, or use `ts_rank_cd` (cover density ranking, faster for very common terms).

### pg_trgm — fuzzy search for typo tolerance

If you want to find "Spring Boot" even when a user types "Sprng Boot", full-text search won't help (it would look for the lexeme "sprng" which doesn't exist). Trigram similarity handles this:

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_posts_title_trgm ON posts USING GIN (title gin_trgm_ops);

-- Find posts with a title similar to 'Sprng Boot'
SELECT title, similarity(title, 'Sprng Boot') AS sim
FROM posts
WHERE title % 'Sprng Boot'  -- % is the similarity threshold operator
ORDER BY sim DESC
LIMIT 10;
```

The `%` operator returns true when `similarity(a, b) > pg_trgm.similarity_threshold` (default 0.3). You can combine both approaches: use full-text search for the main search and add a fallback trigram search when full-text returns zero results.

### Combining full-text search with ts_headline

`ts_headline()` generates a snippet from the original document showing which parts matched the query, with match terms highlighted:

```sql
SELECT title,
       ts_headline('english', content,
                   plainto_tsquery('english', 'spring boot'),
                   'MaxWords=30, MinWords=15, StartSel=<b>, StopSel=</b>') AS snippet
FROM posts
WHERE search_vector @@ plainto_tsquery('english', 'spring boot');
```

Output:
```
"Introduction to Spring Boot" | "...a brief overview of <b>Spring</b> <b>Boot</b> architecture and how it simplifies..."
```

`ts_headline` is expensive (it re-parses the original text) and should be used only in the final presentation layer, not the search query itself.
