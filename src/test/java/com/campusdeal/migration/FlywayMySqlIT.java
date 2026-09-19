package com.campusdeal.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-gate migration test. It is opt-in because Docker is not available
 * in every developer environment: set CAMPUSDEAL_RUN_INTEGRATION=true before
 * `mvn verify` to run it.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "CAMPUSDEAL_RUN_INTEGRATION", matches = "true")
class FlywayMySqlIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
            .withDatabaseName("campusdeal")
            .withUsername("root")
            .withPassword("test-password");

    @Test
    void baselineAndIncrementalMigrationsAreRepeatable() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
}
