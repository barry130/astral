package com.astral.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.AlertChannel;
import com.astral.dao.entity.AlertRecord;
import com.astral.dao.entity.AlertRule;
import com.astral.dao.mapper.AlertChannelMapper;
import com.astral.dao.mapper.AlertRecordMapper;
import com.astral.dao.mapper.AlertRuleMapper;
import com.astral.dao.mapper.StatApiHourlyMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

class AlertChannelCredentialTest {
    @Test
    @SuppressWarnings("unchecked")
    void malformedLegacySecretNeverLeaksThroughTestOrFailureRecord() throws Exception {
        String secret = "private-key-fixture\u0001中文";
        AlertChannel channel = new AlertChannel();
        channel.setId(1L);
        channel.setName("Fixture");
        channel.setEnabled(1);
        channel.setType("WEBHOOK");
        channel.setConfig(
                new ObjectMapper().writeValueAsString(Map.of("url", "http://127.0.0.1:1/test", "secret", secret)));
        AlertChannelMapper channels = mock(AlertChannelMapper.class);
        when(channels.selectById(1L)).thenReturn(channel);
        AlertRuleMapper rules = mock(AlertRuleMapper.class);
        AlertRecordMapper records = mock(AlertRecordMapper.class);
        StatErrorLogMapper errors = mock(StatErrorLogMapper.class);
        when(errors.selectCount(any())).thenReturn(1L);
        AlertRule rule = new AlertRule();
        rule.setId(2L);
        rule.setName("Fixture rule");
        rule.setChannelId(1L);
        rule.setMetric(AlertEngine.METRIC_SERVER_ERROR);
        rule.setThreshold(1L);
        when(rules.selectList(any())).thenReturn(List.of(rule));
        AlertEngine engine = new AlertEngine(
                rules,
                channels,
                records,
                errors,
                mock(StatApiHourlyMapper.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                new WebhookUrlPolicy("http://127.0.0.1:1", true));
        AlertAdminController controller = new AlertAdminController(channels, rules, records, engine);

        BusinessException failure = assertThrows(BusinessException.class, () -> controller.testChannel(1L));
        assertFalse(failure.getMessage().contains("private-key-fixture"));
        engine.evaluate();
        var saved = org.mockito.ArgumentCaptor.forClass(AlertRecord.class);
        verify(records).insert(saved.capture());
        assertEquals("FAIL", saved.getValue().getStatus());
        assertFalse(saved.getValue().getErrorMsg().contains("private-key-fixture"));
    }

    @Test
    void channelListDoesNotDiscloseSecretOrCredentialBearingWebhookUrl() {
        AlertChannel row = new AlertChannel();
        row.setId(1L);
        row.setType("WEBHOOK");
        row.setName("Fixture");
        String rawConfig =
                "{\"url\":\"https://example.org/hook/private-url-token\",\"secret\":\"private-key-fixture\"}";
        row.setConfig(rawConfig);
        AlertChannelMapper channels = mock(AlertChannelMapper.class);
        when(channels.selectList(any())).thenReturn(List.of(row));
        AlertAdminController controller = new AlertAdminController(
                channels, mock(AlertRuleMapper.class), mock(AlertRecordMapper.class), mock(AlertEngine.class));

        String response = new ObjectMapper().writeValueAsString(controller.channelList());

        assertFalse(response.contains("private-key-fixture"));
        assertFalse(response.contains("private-url-token"));
        assertEquals(rawConfig, row.getConfig(), "reading a projection must not replace stored credentials with masks");
    }

    @Test
    @SuppressWarnings("unchecked")
    void savingMaskedConfigPreservesDeliveryCredentialsAndAllowsExplicitClearing() throws Exception {
        AtomicReference<String> receivedKey = new AtomicReference<>();
        AtomicReference<String> receivedPath = new AtomicReference<>();
        HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/", exchange -> {
            receivedKey.set(exchange.getRequestHeaders().getFirst("X-Astral-Alert"));
            receivedPath.set(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        receiver.start();
        String origin = "http://127.0.0.1:" + receiver.getAddress().getPort();
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:alert_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        MybatisConfiguration config = new MybatisConfiguration();
        config.setEnvironment(new Environment("test", new JdbcTransactionFactory(), source));
        config.addMapper(AlertChannelMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            session.getConnection();
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE sys_alert_channel (id BIGINT PRIMARY KEY, name VARCHAR(64), type VARCHAR(20),
                    config TEXT, enabled INTEGER, remark VARCHAR(255), create_time TIMESTAMP, update_time TIMESTAMP)
                    """);
            jdbc.update(
                    "INSERT INTO sys_alert_channel(id,name,type,config,enabled) VALUES (1,'Fixture','WEBHOOK',?,1)",
                    new ObjectMapper()
                            .writeValueAsString(
                                    Map.of("url", "  " + origin + "/private-url-token  ", "secret", "fixture-key")));
            AlertChannelMapper channels = session.getMapper(AlertChannelMapper.class);
            AlertRuleMapper rules = mock(AlertRuleMapper.class);
            AlertRecordMapper records = mock(AlertRecordMapper.class);
            AlertEngine engine = new AlertEngine(
                    rules,
                    channels,
                    records,
                    mock(StatErrorLogMapper.class),
                    mock(StatApiHourlyMapper.class),
                    mock(ObjectProvider.class),
                    mock(ObjectProvider.class),
                    new WebhookUrlPolicy(origin, true));
            AlertAdminController controller = new AlertAdminController(channels, rules, records, engine);
            AlertAdminController.ChannelRequest body = new AlertAdminController.ChannelRequest();
            body.setName("Renamed");
            body.setType("WEBHOOK");
            body.setConfig(controller.channelList().getData().getFirst().config());

            controller.updateChannel(1L, body);
            controller.testChannel(1L);

            assertEquals("fixture-key", receivedKey.get());
            assertEquals("/private-url-token", receivedPath.get());
            body.setConfig("{\"url\":\"********\",\"secret\":\"\"}");
            controller.updateChannel(1L, body);
            controller.testChannel(1L);
            assertEquals(null, receivedKey.get());
        } finally {
            receiver.stop(0);
        }
    }
}
