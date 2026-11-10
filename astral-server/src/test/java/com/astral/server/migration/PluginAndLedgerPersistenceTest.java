package com.astral.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.astral.common.util.SqlIdentifiers;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceSegmentMapper;
import com.astral.dao.mapper.SysPluginMapper;
import com.astral.plugin.core.PluginStateRepository;
import com.astral.sequence.config.EntityIdSequenceProvider;
import com.astral.sequence.config.SequenceMetaObjectHandler;
import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.service.SequenceAsyncWriter;
import com.astral.storage.entity.StorageUploadChargeEntity;
import com.astral.storage.entity.StorageUploadTicketEntity;
import com.astral.storage.mapper.StorageUploadChargeMapper;
import com.astral.storage.mapper.StorageUploadTicketMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real migrations, PostgreSQL mappers and global ID filling; no application services or network start. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class PluginAndLedgerPersistenceTest {
    @Test
    void concurrentPluginWritesAndNewLedgersUseIndependentGlobalSequences() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        String schema = "plugin_audit_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        SegmentGenerator generator = null;
        try {
            Flyway.configure()
                    .dataSource(url, "astral_audit", "")
                    .locations("classpath:db/migration")
                    .schemas(schema)
                    .defaultSchema(schema)
                    .placeholderReplacement(false)
                    .callbacks(new HistoricalSeedCompatibilityCallback())
                    .load()
                    .migrate();
            var source = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
            var sequenceConfig = configuration(source);
            sequenceConfig.addMapper(SequenceConfigMapper.class);
            sequenceConfig.addMapper(SequenceSegmentMapper.class);
            var sequenceSession = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(sequenceConfig));
            var ids = new EntityIdSequenceProvider(url);
            generator = new SegmentGenerator(
                    sequenceSession.getMapper(SequenceSegmentMapper.class),
                    sequenceSession.getMapper(SequenceConfigMapper.class),
                    ids);
            var businessConfig = configuration(source);
            GlobalConfigUtils.setGlobalConfig(
                    businessConfig,
                    new GlobalConfig()
                            .setDbConfig(new GlobalConfig.DbConfig())
                            .setMetaObjectHandler(
                                    new SequenceMetaObjectHandler(generator, ids, mock(SequenceAsyncWriter.class))));
            businessConfig.addMapper(SysPluginMapper.class);
            businessConfig.addMapper(StorageUploadTicketMapper.class);
            businessConfig.addMapper(StorageUploadChargeMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(businessConfig));
            var states = new PluginStateRepository();
            ReflectionTestUtils.setField(states, "pluginMapper", session.getMapper(SysPluginMapper.class));
            var start = new CountDownLatch(1);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> {
                    start.await();
                    states.saveState("fixture-one", true);
                    return null;
                });
                var second = executor.submit(() -> {
                    start.await();
                    states.saveState("fixture-two", true);
                    return null;
                });
                start.countDown();
                first.get(15, TimeUnit.SECONDS);
                second.get(15, TimeUnit.SECONDS);
            }
            assertTrue(states.loadStates().get("fixture-one"));
            assertTrue(states.loadStates().get("fixture-two"));
            states.saveState("fixture-one", false);
            assertEquals(false, states.loadStates().get("fixture-one"));
            var jdbc = new JdbcTemplate(source);
            assertEquals(
                    2L,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM sys_plugin WHERE plugin_id LIKE 'fixture-%'", Long.class));
            assertTrue(
                    jdbc.queryForObject("SELECT MIN(id) FROM sys_plugin WHERE plugin_id LIKE 'fixture-%'", Long.class)
                            > 1000000L);

            var ticket = new StorageUploadTicketEntity();
            ticket.setUploadId("fixture-ticket");
            ticket.setFolderId(1L);
            ticket.setPayloadJson("{}");
            ticket.setExpiresAt(LocalDateTime.now().plusMinutes(5));
            ticket.setState("PENDING");
            assertEquals(1, session.getMapper(StorageUploadTicketMapper.class).insert(ticket));
            assertEquals(1000001L, ticket.getId());
            assertNotNull(ticket.getCreateTime());
            var charge = new StorageUploadChargeEntity();
            charge.setUploadId("fixture-charge");
            charge.setFileId(1L);
            charge.setFolderId(1L);
            charge.setQuotaDay(LocalDate.now());
            charge.setCharge(true);
            assertEquals(1, session.getMapper(StorageUploadChargeMapper.class).insert(charge));
            assertEquals(1000001L, charge.getId());
            assertNotNull(charge.getCreateTime());
            jdbc.execute("ALTER TABLE sys_plugin ADD CONSTRAINT fixture_reject CHECK (enabled=0) NOT VALID");
            assertThrows(RuntimeException.class, () -> states.saveState("fixture-one", true));
            assertEquals(false, states.loadStates().get("fixture-one"));
        } finally {
            if (generator != null) generator.shutdownPreloadExecutor();
            admin.execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", SqlIdentifiers.requireSafe(schema)));
        }
    }

    private static MybatisConfiguration configuration(DataSource source) {
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        return config;
    }
}
