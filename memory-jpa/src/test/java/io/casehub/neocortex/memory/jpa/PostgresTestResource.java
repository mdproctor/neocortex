package io.casehub.neocortex.memory.jpa;

import io.casehub.neocortex.memory.*;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.Map;

/**
 * Starts a real PostgreSQL container before Quarkus boots and injects concrete JDBC URL.
 * Also enables FTS — application.properties disables it for H2 compatibility.
 *
 * Must be paired with the postgres-dialect-test Surefire execution, which sets
 * quarkus.datasource.db-kind=postgresql before augmentation so Agroal is configured
 * with the PostgreSQL driver class.
 */
public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Override
    public Map<String, String> start() {
        POSTGRES.start();
        return Map.of(
            "quarkus.datasource.db-kind",            "postgresql",
            "quarkus.datasource.jdbc.url",            POSTGRES.getJdbcUrl(),
            "quarkus.datasource.username",            POSTGRES.getUsername(),
            "quarkus.datasource.password",            POSTGRES.getPassword(),
            "quarkus.datasource.devservices.enabled", "false",
            "casehub.memory.jpa.fts.enabled",         "true"
        );
    }

    @Override
    public void stop() {
        if (POSTGRES.isRunning()) POSTGRES.stop();
    }
}
