CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE IF EXISTS knowledge_base
    ALTER COLUMN description TYPE TEXT;

CREATE TABLE IF NOT EXISTS document (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    name VARCHAR(255) NOT NULL,
    file_type VARCHAR(32) NOT NULL,
    storage_path VARCHAR(512) NOT NULL,
    content TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_document_kb_id ON document(kb_id);
CREATE INDEX IF NOT EXISTS idx_document_status ON document(status);

CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    doc_id BIGINT NOT NULL REFERENCES document(id),
    chunk_index INT NOT NULL,
    chunk_text TEXT NOT NULL,
    token_estimate INT NOT NULL DEFAULT 0,
    embedding VECTOR(1536) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_chunk UNIQUE (doc_id, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_document_chunk_doc_id ON document_chunk(doc_id);
CREATE INDEX IF NOT EXISTS idx_document_chunk_kb_id ON document_chunk(kb_id);

CREATE INDEX IF NOT EXISTS idx_document_chunk_embedding
    ON document_chunk
    USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

CREATE TABLE IF NOT EXISTS chat_message (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    references_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_kb_id ON chat_message(kb_id);

INSERT INTO knowledge_base (name, description)
SELECT 'default_kb', 'Mini Ragent default knowledge base'
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_base WHERE name = 'default_kb'
);
