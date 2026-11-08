package com.astral.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Historical upgrade fixtures run exclusively on an explicitly selected disposable database. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class NoticeMigrationCompatibilityTest {
    @Test
    void precreatedEmptySchemaIsNotBaselined() throws Exception {
        withDatabase((url, schema, connection) -> {
            Flyway flyway = configuration(url, schema).load();
            flyway.migrate();
            assertEquals(0, count(connection, "SELECT count(*) FROM flyway_schema_history WHERE type='BASELINE'"));
            assertEquals(
                    1,
                    count(
                            connection,
                            "SELECT count(*) FROM flyway_schema_history WHERE version='20260914001' AND type='SQL'"));
            verifyFinalShape(connection, flyway);
        });
    }

    @Test
    void retriesOriginalMissingTableFailureWithoutChangingSuccessfulChecksums() throws Exception {
        withDatabase((url, schema, connection) -> {
            configuration(url, schema)
                    .skipDefaultCallbacks(true)
                    .target("20261001006")
                    .load()
                    .migrate();
            Map<String, Integer> previous = checksums(connection);
            assertThrows(
                    FlywayException.class,
                    () -> configuration(url, schema)
                            .skipDefaultCallbacks(true)
                            .target("20261001007")
                            .load()
                            .migrate());
            assertEquals(
                    0, count(connection, "SELECT count(*) FROM flyway_schema_history WHERE version='20261001007'"));
            Flyway flyway = configuration(url, schema).load();
            flyway.migrate();
            assertTrue(checksums(connection).entrySet().containsAll(previous.entrySet()));
            verifyFinalShape(connection, flyway);
        });
    }

    @Test
    void existingNoticesKeepContentAndReceivePlatformAndEventConversions() throws Exception {
        withDatabase((url, schema, connection) -> {
            configuration(url, schema)
                    .skipDefaultCallbacks(true)
                    .target("20261001006")
                    .load()
                    .migrate();
            createLegacyNotices(connection);
            configuration(url, schema).target("20261002006").load().migrate();
            connection.createStatement().execute("""
                    INSERT INTO sys_notice(id,channel,title,content,scene) VALUES
                    (920005,'app-ios','Reply','Reply content','feedbackAdminReplied'),
                    (920006,'all','Roles','Roles content','userRolesChanged')
                    """);
            Flyway flyway = configuration(url, schema).load();
            flyway.migrate();
            verifyNotices(connection);
            try (var rows = connection
                    .createStatement()
                    .executeQuery("SELECT channel,sender FROM sys_notice WHERE id=920005")) {
                assertTrue(rows.next());
                assertEquals("all", rows.getString(1));
                assertEquals("feedback", rows.getString(2));
            }
            try (var rows =
                    connection.createStatement().executeQuery("SELECT notice_type FROM sys_notice WHERE id=920006")) {
                assertTrue(rows.next());
                assertEquals("notice", rows.getString(1));
            }
            verifyFinalShape(connection, flyway);
        });
    }

    @Test
    void legacyDatabaseWithoutHistoryBaselinesAndPreservesNotices() throws Exception {
        withDatabase((url, schema, connection) -> {
            connection.createStatement().execute(resource("V20260914001__init.sql"));
            createLegacyNotices(connection);
            Flyway flyway = configuration(url, schema).load();
            flyway.migrate();
            assertEquals(
                    1,
                    count(
                            connection,
                            "SELECT count(*) FROM flyway_schema_history WHERE type='BASELINE' AND version='20260914001'"));
            assertEquals(
                    0,
                    count(
                            connection,
                            "SELECT count(*) FROM flyway_schema_history WHERE type='SQL' AND version='20260914001'"));
            verifyNotices(connection);
            verifyFinalShape(connection, flyway);
        });
    }

    @Test
    void publicTableDoesNotHideMissingNoticeTableInTargetSchema() throws Exception {
        withDatabase((url, schema, connection) -> {
            // The entire server is disposable. Refuse to replace any preexisting sentinel.
            connection.createStatement().execute("CREATE TABLE public.sys_notice (sentinel text PRIMARY KEY)");
            try {
                connection.createStatement().execute("INSERT INTO public.sys_notice VALUES ('untouched')");
                Flyway flyway = configuration(url, schema).load();
                flyway.migrate();
                assertEquals(1, count(connection, "SELECT count(*) FROM public.sys_notice WHERE sentinel='untouched'"));
                assertEquals(
                        1,
                        count(
                                connection,
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='sys_notice'"));
                verifyFinalShape(connection, flyway);
            } finally {
                connection.createStatement().execute("DROP TABLE public.sys_notice");
            }
        });
    }

    @Test
    void secondStartupWaitsBeforeValidationAndBaselineDecision() throws Exception {
        withDatabase((url, schema, connection) -> {
            CountDownLatch validating = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch secondStarted = new CountDownLatch(1);
            AtomicInteger validations = new AtomicInteger();
            Callback pauseFirst = new Callback() {
                @Override
                public boolean supports(Event event, Context context) {
                    return event == Event.BEFORE_VALIDATE;
                }

                @Override
                public boolean canHandleInTransaction(Event event, Context context) {
                    return false;
                }

                @Override
                public String getCallbackName() {
                    return "fixture-startup-barrier";
                }

                @Override
                public void handle(Event event, Context context) {
                    if (validations.incrementAndGet() == 1) {
                        validating.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    } else {
                        assertEquals(
                                0L,
                                release.getCount(),
                                "second startup entered Flyway before the first released its schema lock");
                    }
                }
            };
            var firstFlyway = configuration(url, schema)
                    .callbacks(new HistoricalSeedCompatibilityCallback(), pauseFirst)
                    .load();
            var secondFlyway = configuration(url, schema)
                    .callbacks(new HistoricalSeedCompatibilityCallback(), pauseFirst)
                    .load();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> new PostgresMigrationStrategy().migrate(firstFlyway));
                assertTrue(validating.await(10, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    new PostgresMigrationStrategy().migrate(secondFlyway);
                });
                try {
                    assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
                    assertThrows(TimeoutException.class, () -> second.get(500, TimeUnit.MILLISECONDS));
                } finally {
                    release.countDown();
                }
                first.get(45, TimeUnit.SECONDS);
                second.get(45, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertEquals(2, validations.get());
            verifyFinalShape(connection, configuration(url, schema).load());
        });
    }

    @Test
    void concurrentStartupAppliesEachVersionOnlyOnce() throws Exception {
        withDatabase((url, schema, connection) -> {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                java.util.concurrent.Callable<Void> migrate = () -> {
                    Flyway flyway = configuration(url, schema).load();
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    new PostgresMigrationStrategy().migrate(flyway);
                    return null;
                };
                var first = executor.submit(migrate);
                var second = executor.submit(migrate);
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                first.get(45, TimeUnit.SECONDS);
                second.get(45, TimeUnit.SECONDS);
                assertEquals(0, count(connection, "SELECT count(*) FROM flyway_schema_history WHERE type='BASELINE'"));
                assertEquals(
                        1,
                        count(
                                connection,
                                "SELECT count(*) FROM flyway_schema_history WHERE type='SQL' AND version='20260914001'"));
            }
            assertEquals(
                    0,
                    count(
                            connection,
                            "SELECT count(*) FROM (SELECT version FROM flyway_schema_history WHERE type='SQL' GROUP BY version HAVING count(*)>1) duplicates"));
            verifyFinalShape(connection, configuration(url, schema).load());
        });
    }

    private static void createLegacyNotices(Connection connection) throws Exception {
        connection.createStatement().execute(resource("beforeEachMigrate__ensure_sys_notice.sql"));
        connection.createStatement().execute("""
                ALTER TABLE sys_notice DROP COLUMN scene, DROP COLUMN read_time,
                    DROP COLUMN read_seconds, DROP COLUMN terms_version;
                ALTER TABLE sys_notice ALTER COLUMN channel SET DEFAULT 'app';
                ALTER TABLE sys_notice ALTER COLUMN channel TYPE varchar(16);
                INSERT INTO sys_notice(id,channel,title,content) VALUES
                (920001,'app','app title','app content'),(920002,'pc','pc title','pc content'),
                (920003,'web','web title','web content'),(920004,'all','all title','all content')
                """);
    }

    private static void verifyNotices(Connection connection) throws Exception {
        String[][] expected = {{"app-android,app-ios", "app"}, {"app-windows", "pc"}, {"web", "web"}, {"all", "all"}};
        try (var rows = connection
                .createStatement()
                .executeQuery(
                        "SELECT id,channel,title,content,notice_type FROM sys_notice WHERE id BETWEEN 920001 AND 920004 ORDER BY id")) {
            for (int i = 0; i < expected.length; i++) {
                assertTrue(rows.next());
                assertEquals(920001 + i, rows.getLong(1));
                assertEquals(expected[i][0], rows.getString(2));
                assertEquals(expected[i][1] + " title", rows.getString(3));
                assertEquals(expected[i][1] + " content", rows.getString(4));
                assertEquals("announce", rows.getString(5));
            }
            assertFalse(rows.next());
        }
    }

    private static void verifyFinalShape(Connection connection, Flyway flyway) throws Exception {
        flyway.validate();
        assertEquals(0, flyway.info().pending().length);
        assertEquals(
                26,
                count(
                        connection,
                        "SELECT count(*) FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='sys_notice'"));
        assertEquals(
                96,
                count(
                        connection,
                        "SELECT character_maximum_length FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='sys_notice' AND column_name='channel'"));
        assertEquals(
                48,
                count(
                        connection,
                        "SELECT character_maximum_length FROM information_schema.columns WHERE table_schema=current_schema() AND table_name='sys_notice' AND column_name='sender'"));
        Set<String> expected = Set.of(
                "idx_notice_channel",
                "idx_notice_type",
                "idx_notice_user",
                "idx_notice_show",
                "idx_notice_create_time",
                "idx_sys_notice_user_read",
                "sys_notice_pkey");
        var indexes = new java.util.HashSet<String>();
        try (var rows = connection
                .createStatement()
                .executeQuery(
                        "SELECT indexname FROM pg_indexes WHERE schemaname=current_schema() AND tablename='sys_notice'")) {
            while (rows.next()) {
                indexes.add(rows.getString(1));
            }
        }
        assertEquals(expected, indexes);
        Map<String, Integer> before = checksums(connection);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(before, checksums(connection));
    }

    private static Map<String, Integer> checksums(Connection connection) throws Exception {
        Map<String, Integer> result = new HashMap<>();
        try (var rows = connection
                .createStatement()
                .executeQuery("SELECT version,checksum FROM flyway_schema_history WHERE type='SQL' AND success")) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getInt(2));
            }
        }
        return result;
    }

    private static long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement();
                var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static String resource(String name) throws Exception {
        try (var stream = NoticeMigrationCompatibilityTest.class.getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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

    private static void withDatabase(MigrationAssertion assertion) throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        String schema = "notice_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(url, "astral_audit", "")) {
            try {
                connection.createStatement().execute("CREATE SCHEMA " + schema);
                connection.setSchema(schema);
                assertion.run(url, schema, connection);
            } finally {
                connection.createStatement().execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @FunctionalInterface
    private interface MigrationAssertion {
        void run(String url, String schema, Connection connection) throws Exception;
    }
}
