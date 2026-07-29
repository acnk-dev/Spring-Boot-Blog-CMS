-- V3: Create tags table
CREATE TABLE tags (

                      id BIGSERIAL PRIMARY KEY,
                      name VARCHAR(50) UNIQUE NOT NULL,
                      slug VARCHAR(60) UNIQUE NOT NULL,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on slug for faster lookups
CREATE INDEX idx_tags_slug ON tags(slug);

-- Create index on name for searching
CREATE INDEX idx_tags_name ON tags(name);