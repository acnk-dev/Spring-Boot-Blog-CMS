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