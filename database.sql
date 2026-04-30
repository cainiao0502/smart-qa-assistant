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
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Add error_message column if table already exists without it
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'document' AND column_name = 'error_message'
    ) THEN
        ALTER TABLE document ADD COLUMN error_message TEXT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_document_kb_id ON document(kb_id);
CREATE INDEX IF NOT EXISTS idx_document_status ON document(status);

CREATE TABLE IF NOT EXISTS document_chunk (
    id BIGSERIAL PRIMARY KEY,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    doc_id BIGINT NOT NULL REFERENCES document(id),
    chunk_index INT NOT NULL,
    chunk_text TEXT NOT NULL,
    token_estimate INT NOT NULL DEFAULT 0,
    paragraph_index INT DEFAULT 0,
    embedding VECTOR(1536) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_document_chunk UNIQUE (doc_id, chunk_index)
);

-- Add paragraph_index column if table already exists without it
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'document_chunk' AND column_name = 'paragraph_index'
    ) THEN
        ALTER TABLE document_chunk ADD COLUMN paragraph_index INT DEFAULT 0;
    END IF;
END $$;

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
    run_id VARCHAR(64),
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    references_json JSONB,
    tool_calls_json JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'chat_message' AND column_name = 'tool_calls_json'
    ) THEN
        ALTER TABLE chat_message ADD COLUMN tool_calls_json JSONB;
    END IF;
END $$;

DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'chat_message' AND column_name = 'run_id'
    ) THEN
        ALTER TABLE chat_message ADD COLUMN run_id VARCHAR(64);
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_chat_message_session_id ON chat_message(session_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_kb_id ON chat_message(kb_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_run_id ON chat_message(run_id);

CREATE TABLE IF NOT EXISTS agent_run (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL UNIQUE,
    session_id VARCHAR(64) NOT NULL,
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id),
    user_goal TEXT NOT NULL,
    status VARCHAR(32) NOT NULL,
    final_answer TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_run_session_id ON agent_run(session_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_kb_id ON agent_run(kb_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON agent_run(status);

CREATE TABLE IF NOT EXISTS agent_step (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES agent_run(run_id),
    step_index INT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    tool_name VARCHAR(128),
    arguments_json JSONB,
    reason TEXT,
    observation_summary TEXT,
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_agent_step_run_id ON agent_step(run_id);
CREATE INDEX IF NOT EXISTS idx_agent_step_status ON agent_step(status);

-- 文档异步入库任务表：每次用户触发文档索引时创建一条记录，用于追踪异步处理的状态和进度
CREATE TABLE IF NOT EXISTS document_task (
    id BIGSERIAL PRIMARY KEY,                          -- 任务主键，前端轮询时使用
    doc_id BIGINT NOT NULL REFERENCES document(id),    -- 关联的文档 ID
    kb_id BIGINT NOT NULL REFERENCES knowledge_base(id),-- 关联的知识库 ID
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',     -- 任务状态：PENDING / RUNNING / SUCCESS / FAILED
    progress VARCHAR(32) NOT NULL DEFAULT 'PENDING',   -- 执行步骤：PENDING / PARSING / CHUNKING / EMBEDDING / INDEXING
    error_message TEXT,                                 -- 失败时的错误信息
    parse_duration_ms BIGINT,                           -- 解析步骤耗时（毫秒）
    chunk_duration_ms BIGINT,                           -- 切分步骤耗时（毫秒）
    embed_duration_ms BIGINT,                           -- 向量化步骤耗时（毫秒）
    index_duration_ms BIGINT,                           -- 写入步骤耗时（毫秒）
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Add duration columns if table already exists without them
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'document_task' AND column_name = 'parse_duration_ms'
    ) THEN
        ALTER TABLE document_task ADD COLUMN parse_duration_ms BIGINT;
        ALTER TABLE document_task ADD COLUMN chunk_duration_ms BIGINT;
        ALTER TABLE document_task ADD COLUMN embed_duration_ms BIGINT;
        ALTER TABLE document_task ADD COLUMN index_duration_ms BIGINT;
    END IF;
END $$;

-- 按文档 ID 查询任务（如查某文档的最新任务状态）
CREATE INDEX IF NOT EXISTS idx_document_task_doc_id ON document_task(doc_id);
-- 按状态筛选任务（如查所有正在执行的任务）
CREATE INDEX IF NOT EXISTS idx_document_task_status ON document_task(status);

INSERT INTO knowledge_base (name, description)
SELECT 'default_kb', 'Mini Ragent default knowledge base'
WHERE NOT EXISTS (
    SELECT 1 FROM knowledge_base WHERE name = 'default_kb'
);
