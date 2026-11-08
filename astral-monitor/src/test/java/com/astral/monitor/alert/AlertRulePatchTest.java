package com.astral.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.astral.dao.mapper.AlertChannelMapper;
import com.astral.dao.mapper.AlertRecordMapper;
import com.astral.dao.mapper.AlertRuleMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AlertRulePatchTest {
    @Test
    void omittedEnableAndConcurrentEvaluationSurviveAnEdit() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:alert_patch_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        var jdbc = new JdbcTemplate(source);
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new JdbcTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        var gate = new WriteGate();
        config.addInterceptor(gate);
        config.addMapper(AlertChannelMapper.class);
        config.addMapper(AlertRuleMapper.class);
        try (var session = new MybatisSqlSessionFactoryBuilder().build(config).openSession(true)) {
            session.getConnection();
            jdbc.execute("""
                    CREATE TABLE sys_alert_channel(id BIGINT PRIMARY KEY,name VARCHAR,type VARCHAR,config TEXT,
                    enabled INT,remark VARCHAR,create_time TIMESTAMP,update_time TIMESTAMP)
                    """);
            jdbc.execute("""
                    CREATE TABLE sys_alert_rule(id BIGINT PRIMARY KEY,name VARCHAR,metric VARCHAR,threshold BIGINT,
                    window_minutes INT,channel_id BIGINT,cooldown_minutes INT,enabled INT,last_fired_at TIMESTAMP,
                    create_time TIMESTAMP,update_time TIMESTAMP)
                    """);
            jdbc.update("INSERT INTO sys_alert_channel(id) VALUES(1)");
            jdbc.update(
                    "INSERT INTO sys_alert_rule(id,name,metric,threshold,channel_id,enabled) VALUES(2,'Rule','SERVER_ERROR_COUNT',1,1,1)");
            var controller = new AlertAdminController(
                    session.getMapper(AlertChannelMapper.class),
                    session.getMapper(AlertRuleMapper.class),
                    mock(AlertRecordMapper.class),
                    mock(AlertEngine.class));
            var body = new AlertAdminController.RuleRequest();
            body.setName("Edited");
            body.setMetric("SERVER_ERROR_COUNT");
            body.setThreshold(3L);
            body.setChannelId(1L);
            LocalDateTime fired = LocalDateTime.of(2026, 11, 8, 12, 30);
            gate.before = () -> jdbc.update("UPDATE sys_alert_rule SET enabled=0,last_fired_at=? WHERE id=2", fired);
            controller.updateRule(2L, body);
            var saved = controller.ruleList().getData().getFirst();
            assertEquals(0, saved.get("enabled"));
            assertEquals(fired, saved.get("lastFiredAt"));
            assertEquals("Edited", saved.get("name"));
            assertEquals(3L, saved.get("threshold"));
        }
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class WriteGate implements Interceptor {
        Runnable before;

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            if (before != null) {
                var action = before;
                before = null;
                action.run();
            }
            return invocation.proceed();
        }
    }
}
