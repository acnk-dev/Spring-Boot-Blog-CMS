-- V6: Add full-text search capability to the posts table
--
-- This migration adds a generated stored tsvector column that PostgreSQL
-- keeps automatically in sync whenever title or content changes.
-- A GIN index on that column enables O(log n + k) full-text lookups
-- instead of the O(n) sequential scans required by the previous LIKE query.

ALTER TABLE posts
    ADD COLUMN search_vector tsvector
        GENERATED ALWAYS AS (
            to_tsvector('english',
                        coalesce(title, '') || ' ' || coalesce(content, ''))
            ) STORED;

CREATE INDEX idx_posts_search_vector
    ON posts
    USING GIN (search_vector);
