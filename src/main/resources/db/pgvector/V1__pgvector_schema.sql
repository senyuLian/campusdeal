-- PostgreSQL-only migration for the optional vector backend.
-- Apply this file with the PGVector datasource when
-- CAMPUSDEAL_PGVECTOR_ENABLED=true. The primary Flyway location remains
-- MySQL-specific because the transactional application schema is MySQL.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS document_vectors (
    id VARCHAR(255) PRIMARY KEY,
    title TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL,
    embedding vector(1024) NOT NULL,
    source VARCHAR(128) NOT NULL DEFAULT 'unknown',
    category VARCHAR(128) NOT NULL DEFAULT 'general',
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_document_vectors_embedding
    ON document_vectors USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_document_vectors_source
    ON document_vectors (source, category);
