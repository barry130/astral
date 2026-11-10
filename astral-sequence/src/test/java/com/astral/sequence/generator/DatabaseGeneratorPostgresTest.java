package com.astral.sequence.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.astral.common.util.SqlIdentifiers;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.sql.Connection;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

/** Optional integration test against a disposable, loopback-only PostgreSQL instance. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class DatabaseGeneratorPostgresTest {
    @Test
    void allocationReadsReturnedValueAndNeverReusesCachedAllocation() throws Exception {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setServerNames(new String[] {"127.0.0.1"});
        source.setPortNumbers(new int[] {Integer.parseInt(System.getProperty("audit.postgres.port"))});
        source.setUser("astral_audit");
        source.setDatabaseName("postgres");
        String schema = "sequence_audit_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection admin = source.getConnection()) {
            admin.createStatement().execute(String.format("CREATE SCHEMA %s", SqlIdentifiers.requireSafe(schema)));
            source.setCurrentSchema(schema);
            try (Connection setup = source.getConnection()) {
                setup.createStatement().execute("""
                        CREATE TABLE sequence_statistics (
                        biz_key VARCHAR(64) UNIQUE, current_value BIGINT, total_generate BIGINT,
                        deleted INTEGER DEFAULT 0, create_time TIMESTAMP, update_time TIMESTAMP)
                        """);
                setup.createStatement()
                        .execute("INSERT INTO sequence_statistics(biz_key,current_value) VALUES ('orders',40)");
                MybatisConfiguration config = new MybatisConfiguration();
                config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
                config.addMapper(SequenceStatisticsMapper.class);
                try (var session =
                        new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
                    DatabaseGenerator generator =
                            new DatabaseGenerator(session.getMapper(SequenceStatisticsMapper.class));
                    assertEquals(41L, generator.next("orders"));
                    assertEquals(42L, generator.next("orders"));
                    assertEquals("43,44", generator.batch("orders", 2));
                    assertEquals(1L, generator.next("new_orders"));
                }
            } finally {
                admin.createStatement()
                        .execute(String.format("DROP SCHEMA %s CASCADE", SqlIdentifiers.requireSafe(schema)));
            }
        }
    }
}
