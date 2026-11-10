package com.astral.server.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.config.AiProperties;
import com.astral.ai.config.AiSchemaInitializer;
import com.astral.common.util.SqlIdentifiers;
import com.astral.dao.mapper.SysPluginMapper;
import com.astral.plugin.core.PluginAutoConfiguration;
import com.astral.plugin.core.PluginRegistryImpl;
import com.astral.plugin.core.PluginStateRepository;
import com.astral.schema.SchemaRegistry;
import com.astral.schema.TableSchema;
import com.astral.sequence.config.AsyncConfig;
import com.astral.sequence.config.EntityIdSequenceProvider;
import com.astral.sequence.config.SequenceAutoConfiguration;
import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.plugin.SequencePlugin;
import com.astral.sequence.service.SequenceAsyncWriter;
import com.astral.sequence.service.SequenceHistoryService;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** Real Boot/Flyway/MyBatis/async/sequence graph, with only a disposable datasource and schema catalog fixture. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class PluginStartupContextTest {
    @Test
    @SuppressWarnings("unchecked")
    void bootMigrationPrecedesRegistryAndGlobalIdsWorkAcrossContextRestarts() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        String schema = "startup_audit_" + UUID.randomUUID().toString().replace("-", "");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        // Seed only bundled schemas. Do not call init(), which reads the user's data/schemas directory.
        Map<String, TableSchema> catalog =
                (Map<String, TableSchema>) ReflectionTestUtils.getField(SchemaRegistry.class, "SCHEMA_CACHE");
        assertNotNull(catalog);
        var previous = new HashMap<>(catalog);
        try {
            catalog.clear();
            ReflectionTestUtils.invokeMethod(SchemaRegistry.class, "loadClasspathSchemas");
            admin.execute(String.format("CREATE SCHEMA %s", SqlIdentifiers.requireSafe(schema)));
            var runner = new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(FlywayAutoConfiguration.class, MybatisPlusAutoConfiguration.class))
                    .withUserConfiguration(Fixture.class)
                    .withPropertyValues(
                            "spring.datasource.url=" + url + "?currentSchema=" + schema,
                            "spring.flyway.schemas=" + schema,
                            "spring.flyway.default-schema=" + schema,
                            "spring.flyway.placeholder-replacement=false",
                            "mybatis-plus.configuration.map-underscore-to-camel-case=true");
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(PostgresMigrationStrategy.class));
                assertEquals(0, context.getBean(Flyway.class).info().pending().length);
                assertTrue(context.getBean(PluginRegistryImpl.class).isEnabled("sequence"));
                var state = context.getBean(PluginStateRepository.class);
                state.saveState("startup-fixture", false);
                assertEquals(false, state.loadStates().get("startup-fixture"));
                var jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                assertTrue(
                        jdbc.queryForObject("SELECT id FROM sys_plugin WHERE plugin_id='startup-fixture'", Long.class)
                                > 1000000L);
                context.getBean(SequenceAsyncWriter.class)
                        .saveHistoryAsync("startup-fixture", "SEGMENT", 7)
                        .join();
                assertEquals(
                        1L,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sequence_history WHERE biz_key='startup-fixture'", Long.class));
                // Execute the real ResourceDatabasePopulator fallback twice, including dollar-quoted SQL.
                var initializer = new AiSchemaInitializer();
                ReflectionTestUtils.setField(initializer, "dataSource", context.getBean(DataSource.class));
                ReflectionTestUtils.setField(initializer, "aiProperties", new AiProperties());
                initializer.init();
                initializer.init();
                assertEquals(
                        1L,
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname=current_schema() AND indexname='idx_ai_chunk_grams'",
                                Long.class));
                context.getBean(Flyway.class).validate();
            });
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                assertEquals(
                        false,
                        context.getBean(PluginStateRepository.class)
                                .loadStates()
                                .get("startup-fixture"));
                assertTrue(context.getBean(PluginRegistryImpl.class).isEnabled("sequence"));
            });
        } finally {
            catalog.clear();
            catalog.putAll(previous);
            admin.execute(String.format("DROP SCHEMA IF EXISTS %s CASCADE", SqlIdentifiers.requireSafe(schema)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @MapperScan(basePackageClasses = SysPluginMapper.class)
    @Import({
        PostgresMigrationStrategy.class,
        PluginAutoConfiguration.class,
        PluginRegistryImpl.class,
        PluginStateRepository.class,
        SequenceAutoConfiguration.class,
        AsyncConfig.class,
        EntityIdSequenceProvider.class,
        SegmentGenerator.class,
        SequencePlugin.class,
        SequenceAsyncWriter.class,
        SequenceHistoryService.class
    })
    static class Fixture {
        @Bean
        DataSource dataSource(Environment environment) {
            return new DriverManagerDataSource(
                    environment.getRequiredProperty("spring.datasource.url"), "astral_audit", "");
        }

        @Bean
        HistoricalSeedCompatibilityCallback historicalSeedCompatibilityCallback() {
            return new HistoricalSeedCompatibilityCallback();
        }
    }
}
