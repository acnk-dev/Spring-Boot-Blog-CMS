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

-- Create index on user_id for author queries
CREATE INDEX idx_posts_user_id ON posts(user_id);

-- Create index on category_id for category filtering
CREATE INDEX idx_posts_category_id ON posts(category_id);

-- Create index on slug for URL lookups
CREATE INDEX idx_posts_slug ON posts(slug);

-- Create index on status for filtering published posts
CREATE INDEX idx_posts_status ON posts(status);

-- Create index on is_deleted for soft delete queries
CREATE INDEX idx_posts_is_deleted ON posts(is_deleted);

-- Create index on published_at for chronological sorting
CREATE INDEX idx_posts_published_at ON posts(published_at);

-- Composite index for common query: published, not deleted, ordered by date
CREATE INDEX idx_posts_published_active ON posts(status, is_deleted, published_at DESC);