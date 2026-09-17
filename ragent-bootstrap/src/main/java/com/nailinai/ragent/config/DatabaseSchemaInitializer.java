package com.nailinai.ragent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
public class DatabaseSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);

    @Bean
    public ApplicationRunner ensureChatPersistenceSchema(JdbcTemplate jdbcTemplate) {
        return args -> {
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_name = 'chat_message'
                              AND column_name = 'tool_calls_json'
                        ) THEN
                            ALTER TABLE chat_message ADD COLUMN tool_calls_json JSONB;
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_name = 'chat_message'
                              AND column_name = 'kb_id'
                              AND is_nullable = 'NO'
                        ) THEN
                            ALTER TABLE chat_message ALTER COLUMN kb_id DROP NOT NULL;
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_name = 'agent_run'
                              AND column_name = 'kb_id'
                              AND is_nullable = 'NO'
                        ) THEN
                            ALTER TABLE agent_run ALTER COLUMN kb_id DROP NOT NULL;
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_name = 'document_chunk'
                              AND column_name = 'tsv'
                        ) THEN
                            ALTER TABLE document_chunk
                                ADD COLUMN tsv tsvector
                                GENERATED ALWAYS AS (
                                    to_tsvector('simple', coalesce(chunk_text, ''))
                                ) STORED;
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM pg_indexes
                            WHERE indexname = 'idx_document_chunk_tsv'
                        ) THEN
                            CREATE INDEX idx_document_chunk_tsv
                                ON document_chunk USING gin (tsv);
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_name = 'document_task'
                              AND column_name = 'retry_count'
                        ) THEN
                            ALTER TABLE document_task ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_name = 'chat_message'
                              AND column_name = 'owner_user_id'
                        ) THEN
                            ALTER TABLE chat_message ADD COLUMN owner_user_id BIGINT;
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM pg_indexes
                            WHERE indexname = 'idx_chat_message_owner'
                        ) THEN
                            CREATE INDEX idx_chat_message_owner
                                ON chat_message (owner_user_id);
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    CREATE TABLE IF NOT EXISTS t_user (
                        id BIGSERIAL PRIMARY KEY,
                        username VARCHAR(64) NOT NULL UNIQUE,
                        password_hash VARCHAR(255) NOT NULL,
                        role VARCHAR(16) NOT NULL DEFAULT 'user',
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    );
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_name = 'knowledge_base'
                              AND column_name = 'owner_user_id'
                        ) THEN
                            ALTER TABLE knowledge_base ADD COLUMN owner_user_id BIGINT;
                        END IF;
                    END $$;
                    """);
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (
                            SELECT 1
                            FROM pg_indexes
                            WHERE indexname = 'idx_knowledge_base_owner'
                        ) THEN
                            CREATE INDEX idx_knowledge_base_owner
                                ON knowledge_base (owner_user_id);
                        END IF;
                    END $$;
                    """);
            log.info("Database schema check completed for chat_message and agent_run persistence compatibility");
        };
    }
}
