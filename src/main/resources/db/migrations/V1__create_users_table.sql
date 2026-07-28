-- V1: Create users table
CREATE TABLE users (
                       id BIGSERIAL PRIMARY KEY,
                       email VARCHAR(255) UNIQUE NOT NULL,
                       password VARCHAR(255) NOT NULL,

                       name VARCHAR(100) NOT NULL,
                       role VARCHAR(20) NOT NULL CHECK (role IN ('SUBSCRIBER', 'AUTHOR', 'ADMIN')),
                       email_verified BOOLEAN NOT NULL DEFAULT FALSE,
                       bio VARCHAR(500),
                       avatar_url VARCHAR(255),
                       is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
                       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Create index on email for faster lookups
CREATE INDEX idx_users_email ON users(email);

-- Create index on role for filtering
CREATE INDEX idx_users_role ON users(role);

-- Create index on is_deleted for soft delete queries
CREATE INDEX idx_users_is_deleted ON users(is_deleted);