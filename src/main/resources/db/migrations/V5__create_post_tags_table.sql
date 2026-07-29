-- V5: Create post_tags junction table for ManyToMany relationship
CREATE TABLE post_tags (

                           post_id BIGINT NOT NULL REFERENCES posts(id) ON DELETE CASCADE,
                           tag_id BIGINT NOT NULL REFERENCES tags(id) ON DELETE CASCADE,
                           PRIMARY KEY (post_id, tag_id)
);

-- Create index on tag_id for reverse lookups (posts by tag)
CREATE INDEX idx_post_tags_tag_id ON post_tags(tag_id);