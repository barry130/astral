package com.astral.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.util.SqlIdentifiers;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Executes the real migration chain only on explicitly selected disposable loopback PostgreSQL. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class FreshDatabaseMigrationTest {
    private static FluentConfiguration configuration(String url, String schema) {
        return Flyway.configure()
                .dataSource(url, "astral_audit", "")
                .locations("classpath:db/migration")
                .schemas(schema)
                .defaultSchema(schema)
                .baselineOnMigrate(true)
                .baselineVersion("20260914001")
                .placeholderReplacement(false)
                .callbacks(new HistoricalSeedCompatibilityCallback());
    }

    @Test
    void emptyDatabaseMigratesAllVersionsWithoutRewritingHistory() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        String schema = "migration_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, "astral_audit", "")) {
            try {
                Flyway flyway = configuration(url, schema).load();
                flyway.migrate();
                flyway.validate();
                assertEquals(0, flyway.info().pending().length);
                assertNotNull(flyway.info().current());
                assertEquals(0, flyway.migrate().migrationsExecuted);
            } finally {
                connection
                        .createStatement()
                        .execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", SqlIdentifiers.requireSafe(schema)));
            }
        }
    }

    @Test
    void storageLedgersBackfillConsumptionAndUseIndependentGlobalSequences() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        String schema = "migration_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, "astral_audit", "")) {
            try {
                configuration(url, schema).target("20261108001").load().migrate();
                connection.setSchema(schema);
                connection.createStatement().execute("""
                        INSERT INTO sys_storage_file(id,public_id,upload_id,folder_id,storage_config_id,uploader_id,status)
                        VALUES(2000001,'fixture-live','fixture-upload',11,5,'fixture','AVAILABLE'),
                        (2000002,'fixture-rejected',NULL,11,5,'fixture','FAILED')
                        """);
                var upgrade = configuration(url, schema).load();
                upgrade.migrate();
                upgrade.validate();
                assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM sys_storage_upload_charge"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys_storage_upload_charge WHERE charge"));
                assertEquals(
                        2000002,
                        scalar(
                                connection,
                                "SELECT current_max_value FROM sequence_segment WHERE biz_key='sys_storage_upload_charge_id'"));
                assertEquals(
                        1000000,
                        scalar(
                                connection,
                                "SELECT current_max_value FROM sequence_segment WHERE biz_key='sys_storage_upload_ticket_id'"));
                connection.createStatement().execute("DELETE FROM sys_storage_file WHERE id IN (2000001,2000002)");
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys_storage_upload_charge WHERE charge"));
                assertThrows(
                        java.sql.SQLException.class,
                        () -> connection.createStatement().execute("""
                        INSERT INTO sys_storage_upload_charge(id,upload_id,file_id,folder_id,actor_id,quota_day,charge)
                        VALUES(2000003,'fixture-upload',2000001,11,'fixture',CURRENT_DATE,true)
                        """));
                assertEquals(0, upgrade.migrate().migrationsExecuted);
            } finally {
                connection
                        .createStatement()
                        .execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", SqlIdentifiers.requireSafe(schema)));
            }
        }
    }

    @Test
    void existingTesterPermissionAndRoleReferenceSurviveHistoricalSeed() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        String schema = "migration_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, "astral_audit", "")) {
            try {
                configuration(url, schema).target("20260925001").load().migrate();
                connection.setSchema(schema);
                connection.createStatement().execute("""
                        INSERT INTO sys_permission(id,permission_code,permission_name) VALUES (900000,'qt_tester','Preserved tester');
                        INSERT INTO sys_role(id,role_code,role_name) VALUES (900000,'fixture_role','Fixture role');
                        INSERT INTO sys_role_permission(id,role_id,permission_id) VALUES (900000,900000,900000)
                        """);

                Flyway upgrade =
                        configuration(url, schema).target("20260926001").load();
                upgrade.migrate();
                upgrade.validate();
                try (var rows = connection.createStatement().executeQuery("""
                        SELECT p.permission_name FROM sys_permission p JOIN sys_role_permission rp
                        ON rp.permission_id=p.id WHERE rp.role_id=900000 AND p.id=900000
                        """)) {
                    org.junit.jupiter.api.Assertions.assertTrue(rows.next());
                    assertEquals("Preserved tester", rows.getString(1));
                }
                configuration(url, schema).load().migrate();
                assertEquals(2, scalar(connection, """
                        SELECT count(*) FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id
                        WHERE rp.role_id=900000 AND p.permission_code IN
                        ('user:qt:update:channel:beta','user:qt:source:channel:beta')
                        """));
            } finally {
                connection
                        .createStatement()
                        .execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", SqlIdentifiers.requireSafe(schema)));
            }
        }
    }

    @Test
    void multipleLegacyRulesKeepTheirRoutesAndGainDistinctEmailChannels() throws Exception {
        withLegacyRules((url, schema, connection) -> {
            Flyway upgrade = configuration(url, schema).target("20261029001").load();
            upgrade.migrate();
            upgrade.validate();
            try (var rows = connection.createStatement().executeQuery("""
                    SELECT r.id, c.channel, c.enabled, c.platform, x.recipient_type, x.recipient_value
                    FROM sys_notify_rule r JOIN sys_notify_rule_channel c ON c.rule_id=r.id
                    JOIN sys_notify_rule_recipient x ON x.channel_id=c.id
                    WHERE r.id IN (900001,900002) ORDER BY r.id,c.channel
                    """)) {
                assertTrue(rows.next());
                assertEquals(900001, rows.getLong(1));
                assertEquals("EMAIL", rows.getString(2));
                assertEquals("email", rows.getString(6));
                assertTrue(rows.next());
                assertEquals("SMS", rows.getString(2));
                assertEquals(0, rows.getInt(3));
                assertEquals("app-ios", rows.getString(4));
                assertEquals("FIXED", rows.getString(5));
                assertEquals("13800000001", rows.getString(6));
                assertTrue(rows.next());
                assertEquals(900002, rows.getLong(1));
                assertEquals("EMAIL", rows.getString(2));
                assertEquals("email", rows.getString(6));
                assertTrue(rows.next());
                assertEquals("INAPP", rows.getString(2));
                assertEquals("all", rows.getString(4));
                assertEquals("PAYLOAD_FIELD", rows.getString(5));
                assertEquals("userId", rows.getString(6));
                org.junit.jupiter.api.Assertions.assertFalse(rows.next());
            }
        });
    }

    @Test
    void partiallySplitRulesKeepChannelIdsAndGainDistinctRecipients() throws Exception {
        withLegacyRules((url, schema, connection) -> {
            // A legitimate partially upgraded shape: channels exist and the old recipient column is gone.
            // Otherwise the original DO block would create recipients before the defective final INSERT.
            String split;
            try (var stream =
                    getClass().getResourceAsStream("/db/migration/V20261029001__notify_rule_channel_split.sql")) {
                split = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            connection
                    .createStatement()
                    .execute(split.substring(
                            0,
                            split.indexOf(
                                    "-- ---------------------------------------------------------------------------")));
            connection.createStatement().execute("""
                    INSERT INTO sys_notify_rule_channel(id,rule_id,channel) VALUES
                    (950001,900001,'EMAIL'),(950002,900002,'EMAIL');
                    ALTER TABLE sys_notify_rule DROP COLUMN recipient_type;
                    ALTER TABLE sys_notify_rule DROP COLUMN recipient_value
                    """);
            configuration(url, schema).target("20261029001").load().migrate();
            try (var rows = connection.createStatement().executeQuery("""
                    SELECT c.id,x.id,x.recipient_type,x.recipient_value FROM sys_notify_rule_channel c
                    JOIN sys_notify_rule_recipient x ON x.channel_id=c.id
                    WHERE c.id IN (950001,950002) ORDER BY c.id
                    """)) {
                assertTrue(rows.next());
                assertEquals(950001, rows.getLong(1));
                long first = rows.getLong(2);
                assertEquals("PAYLOAD_FIELD", rows.getString(3));
                assertEquals("email", rows.getString(4));
                assertTrue(rows.next());
                assertEquals(950002, rows.getLong(1));
                assertTrue(rows.getLong(2) > first);
                assertEquals("PAYLOAD_FIELD", rows.getString(3));
                assertEquals("email", rows.getString(4));
                org.junit.jupiter.api.Assertions.assertFalse(rows.next());
            }
        });
    }

    private void withLegacyRules(MigrationAssertion assertion) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        String schema = "migration_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, "astral_audit", "")) {
            try {
                configuration(url, schema).target("20261028003").load().migrate();
                connection.setSchema(schema);
                connection.createStatement().execute("""
                        INSERT INTO sys_notify_rule(id,rule_name,event_code,channel,template_id,
                            recipient_type,recipient_value,enabled,platform) VALUES
                        (900001,'Fixture SMS','changePasswordByEmail','SMS',1,'FIXED','13800000001',0,'app-ios'),
                        (900002,'Fixture INAPP','changePasswordByEmail','INAPP',2,'PAYLOAD_FIELD','userId',1,'all')
                        """);
                assertion.run(url, schema, connection);
            } finally {
                connection
                        .createStatement()
                        .execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", SqlIdentifiers.requireSafe(schema)));
            }
        }
    }

    @Test
    void replacementFailureRollsBackMigrationAndAllowsCleanRetry() throws Exception {
        withLegacyRules((url, schema, connection) -> {
            String split;
            try (var stream =
                    getClass().getResourceAsStream("/db/migration/V20261029001__notify_rule_channel_split.sql")) {
                split = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            connection
                    .createStatement()
                    .execute(split.substring(
                            0,
                            split.indexOf(
                                    "-- ---------------------------------------------------------------------------")));
            connection.createStatement().execute("""
                    ALTER TABLE sys_notify_rule_channel ADD CONSTRAINT fixture_email_failure CHECK(channel<>'EMAIL')
                    """);
            Flyway upgrade = configuration(url, schema).target("20261029001").load();
            assertThrows(org.flywaydb.core.api.FlywayException.class, upgrade::migrate);
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sys_notify_rule_channel"));
            assertEquals(
                    0, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE version='20261029001'"));
            assertEquals(
                    2,
                    scalar(
                            connection,
                            "SELECT count(*) FROM sys_notify_rule WHERE id IN (900001,900002) AND channel IN ('SMS','INAPP')"));
            connection
                    .createStatement()
                    .execute("ALTER TABLE sys_notify_rule_channel DROP CONSTRAINT fixture_email_failure");
            upgrade.migrate();
            upgrade.validate();
            assertEquals(
                    2,
                    scalar(
                            connection,
                            "SELECT count(*) FROM sys_notify_rule_channel WHERE rule_id IN (900001,900002) AND channel='EMAIL'"));
        });
    }

    private static long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    @FunctionalInterface
    private interface MigrationAssertion {
        void run(String url, String schema, Connection connection) throws Exception;
    }
}
