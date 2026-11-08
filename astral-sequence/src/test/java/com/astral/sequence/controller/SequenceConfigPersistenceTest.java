package com.astral.sequence.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.astral.common.exception.BusinessException;
import com.astral.dao.mapper.SequenceConfigMapper;
import com.astral.dao.mapper.SequenceHistoryMapper;
import com.astral.dao.mapper.SequenceSegmentMapper;
import com.astral.dao.mapper.SequenceStatisticsMapper;
import com.astral.sequence.config.EntityIdSequenceProvider;
import com.astral.sequence.controller.dto.SequenceConfigUpdate;
import com.astral.sequence.generator.DatabaseGenerator;
import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.generator.SimpleGenerator;
import com.astral.sequence.generator.SnowflakeGenerator;
import com.astral.sequence.service.GeneratorFactory;
import com.astral.sequence.service.SequenceAsyncWriter;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class SequenceConfigPersistenceTest {
    @Test
    void commitInvalidatesConfigurationLoadedWhileDisableWasUncommitted() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:sequence_config_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        try (var keepAlive = source.getConnection();
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE sequence_config(id BIGINT PRIMARY KEY, biz_key VARCHAR UNIQUE, sequence_type VARCHAR,
                    step INT, date_format VARCHAR, prefix VARCHAR, suffix VARCHAR, min_value BIGINT, enabled BOOLEAN,
                    description VARCHAR, create_time TIMESTAMP, update_time TIMESTAMP, deleted INT DEFAULT 0)
                    """);
            jdbc.execute(
                    "INSERT INTO sequence_config(id,biz_key,sequence_type,step,prefix,min_value,enabled) VALUES(1,'orders','SIMPLE',1000,'ORD',1,true)");
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(SequenceConfigMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var mapper = session.getMapper(SequenceConfigMapper.class);
            var ids = new EntityIdSequenceProvider("jdbc:h2:mem:fixture");
            var factory = new GeneratorFactory(
                    mock(SnowflakeGenerator.class),
                    new SimpleGenerator(),
                    mock(DatabaseGenerator.class),
                    mock(SegmentGenerator.class),
                    mock(SequenceHistoryMapper.class),
                    mapper,
                    ids,
                    mock(SequenceAsyncWriter.class));
            var controller = new SequenceConfigController(
                    mapper, mock(SequenceStatisticsMapper.class), mock(SequenceSegmentMapper.class), ids, factory);
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            transaction.executeWithoutResult(status -> {
                var body = new SequenceConfigUpdate();
                body.setBizKey("orders");
                body.setEnabled(false);
                var saved = controller.update(1L, body).getData();
                assertEquals("ORD", saved.getPrefix());
                try {
                    assertEquals(
                            1L,
                            executor.submit(() -> factory.next("orders", "SIMPLE"))
                                    .get(5, TimeUnit.SECONDS));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            assertEquals(
                    "SEQ009",
                    assertThrows(BusinessException.class, () -> factory.next("orders", "SIMPLE"))
                            .getErrorCode());
            var body = new SequenceConfigUpdate();
            body.setBizKey("orders");
            body.setPrefix("");
            transaction.executeWithoutResult(status -> controller.update(1L, body));
            assertEquals(false, mapper.selectById(1L).getEnabled());
            assertEquals(null, mapper.selectById(1L).getPrefix());
            CountDownLatch modifying = new CountDownLatch(1);
            var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
            transaction.executeWithoutResult(status -> {
                mapper.lockById(1L);
                future.set(executor.submit(() -> {
                    modifying.countDown();
                    transaction.executeWithoutResult(other -> {
                        var prefix = new SequenceConfigUpdate();
                        prefix.setPrefix("NEW");
                        controller.update(1L, prefix);
                    });
                }));
                try {
                    assertTrue(modifying.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                jdbc.update("UPDATE sequence_config SET enabled=false,description='Concurrent description' WHERE id=1");
            });
            future.get().get(5, TimeUnit.SECONDS);
            var persisted = controller.getByBizKey("orders").getData();
            assertEquals("NEW", persisted.getPrefix());
            assertEquals(false, persisted.getEnabled());
            assertEquals("Concurrent description", persisted.getDescription());
        }
    }
}
