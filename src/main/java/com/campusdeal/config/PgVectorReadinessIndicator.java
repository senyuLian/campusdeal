package com.campusdeal.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** Readiness probe for the explicitly enabled PGVector capability. */
@Component("campusDealPgVector")
public class PgVectorReadinessIndicator implements HealthIndicator {

    @Value("${campusdeal.pgvector.enabled:false}")
    private boolean enabled;
    @Value("${campusdeal.pgvector.url:}")
    private String url;
    @Value("${campusdeal.pgvector.username:}")
    private String username;
    @Value("${campusdeal.pgvector.password:}")
    private String password;
    @Value("${campusdeal.deepseek.embedding-dimension:1024}")
    private int dimension = 1024;

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("state", "DISABLED").build();
        }
        if (url == null || url.isBlank() || !url.startsWith("jdbc:postgresql:")) {
            return Health.down().withDetail("state", "MISCONFIGURED")
                    .withDetail("reason", "CAMPUSDEAL_PGVECTOR_URL is missing or not PostgreSQL").build();
        }
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return Health.down().withDetail("state", "MISCONFIGURED")
                    .withDetail("reason", "PGVector credentials are missing").build();
        }
        if (dimension <= 0 || dimension > 4096) {
            return Health.down().withDetail("state", "MISCONFIGURED")
                    .withDetail("reason", "embedding dimension must be between 1 and 4096").build();
        }
        try {
            DataSource dataSource = new DriverManagerDataSource(url, username, password);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Boolean extension = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'vector')", Boolean.class);
            Boolean table = jdbc.queryForObject(
                    "SELECT to_regclass('document_vectors') IS NOT NULL", Boolean.class);
            if (!Boolean.TRUE.equals(extension) || !Boolean.TRUE.equals(table)) {
                return Health.down().withDetail("state", "SCHEMA_UNAVAILABLE")
                        .withDetail("extension", Boolean.TRUE.equals(extension))
                        .withDetail("table", Boolean.TRUE.equals(table))
                        .withDetail("dimension", dimension).build();
            }
            return Health.up().withDetail("state", "READY").withDetail("dimension", dimension).build();
        } catch (Exception e) {
            // Do not expose URLs, usernames, or driver exception text in a
            // health response; operators still get an actionable state.
            return Health.down().withDetail("state", "UNAVAILABLE")
                    .withDetail("reason", "PostgreSQL/PGVector connectivity check failed")
                    .withDetail("dimension", dimension).build();
        }
    }
}
