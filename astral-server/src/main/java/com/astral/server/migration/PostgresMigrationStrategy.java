package com.astral.server.migration;

import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.stereotype.Component;

/** Serializes startup before Flyway's empty-schema/baseline decision. */
@Component
public final class PostgresMigrationStrategy implements FlywayMigrationStrategy {
    @Override
    public void migrate(Flyway flyway) {
        var configuration = flyway.getConfiguration();
        try (var connection = configuration.getDataSource().getConnection()) {
            if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
                flyway.migrate();
                return;
            }
            String schema = configuration.getDefaultSchema();
            if (schema == null) {
                String[] schemas = configuration.getSchemas();
                schema = schemas.length > 0 ? schemas[0] : connection.getSchema();
            }
            // A dedicated transaction keeps the lock across Flyway's own connections and
            // commits. Rollback releases it even when a pooled connection is returned.
            connection.setAutoCommit(false);
            try {
                try (var timeout = connection.createStatement()) {
                    timeout.execute("SET LOCAL lock_timeout = '5min'");
                }
                try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                    lock.setString(1, "astral:flyway-startup:" + schema + ":" + configuration.getTable());
                    lock.execute();
                }
                flyway.migrate();
            } finally {
                connection.rollback();
            }
        } catch (SQLException error) {
            throw new FlywayException("Unable to acquire or release the database migration startup lock", error);
        }
    }
}
