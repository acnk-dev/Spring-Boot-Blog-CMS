-- V7: Create comments table
-- Comments are soft-deleted (is_deleted flag) and linked to posts and users.

CREATE TABLE comments (
                          id         BIGSERIAL PRIMARY KEY,
                          post_id    BIGINT       NOT NULL REFERENCES posts(id),
                          user_id    BIGINT       NOT NULL REFERENCES users(id),
                          body       TEXT         NOT NULL,
                          is_deleted BOOLEAN      NOT NULL DEFAULT FALSE,
                          created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
                          updated_at TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_comments_post_id ON comments(post_id);
CREATE INDEX idx_comments_user_id ON comments(user_id);
