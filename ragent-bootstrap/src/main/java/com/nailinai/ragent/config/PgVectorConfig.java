package com.nailinai.ragent.config;

import com.pgvector.PGvector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Configuration
public class PgVectorConfig {

    @Bean
    public PgVectorRegistrar pgVectorRegistrar(DataSource dataSource) {
        return new PgVectorRegistrar(dataSource);
    }

    public static class PgVectorRegistrar {

        public PgVectorRegistrar(DataSource dataSource) {
            try (Connection connection = dataSource.getConnection()) {
                PGvector.registerTypes(connection);
            } catch (SQLException ex) {
                throw new IllegalStateException("failed to register pgvector types", ex);
            }
        }
    }
}
