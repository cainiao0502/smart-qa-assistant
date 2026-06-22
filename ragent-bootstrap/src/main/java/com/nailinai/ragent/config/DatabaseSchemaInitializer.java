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
            log.info("Database schema check completed for chat_message and agent_run persistence compatibility");
        };
    }
}
