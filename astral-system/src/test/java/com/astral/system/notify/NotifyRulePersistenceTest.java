package com.astral.system.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.controller.NotifyRuleController;
import com.astral.system.dto.NotifyRuleSaveDto;
import com.astral.system.mail.MailService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.ObjectMapper;

/** Full-save controller and actual publisher through MyBatis, without real notification delivery. */
class NotifyRulePersistenceTest {
    @Test
    void clearingTemplateAndPlatformSwitchesToAutomaticTemplateAndAllPlatforms() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:rule_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        try (var keepAlive = source.getConnection()) {
            var jdbc = new JdbcTemplate(source);
            var json = new ObjectMapper();
            for (String name : List.of(
                    "sys_notify_rule",
                    "sys_notify_rule_channel",
                    "sys_notify_rule_recipient",
                    "sys_mail_template",
                    "sys_notice")) {
                var table = json.readTree(
                        Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + name + ".json")));
                var columns = new ArrayList<String>();
                for (var field : table.path("fields")) {
                    columns.add(field.path("columnName").asString() + " "
                            + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                            + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
                }
                jdbc.execute("CREATE TABLE " + name + " (" + String.join(",", columns) + ")");
            }
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            var ids = new AtomicLong(1000);
            GlobalConfigUtils.setGlobalConfig(
                    config,
                    new GlobalConfig()
                            .setDbConfig(new GlobalConfig.DbConfig())
                            .setMetaObjectHandler(new MetaObjectHandler() {
                                @Override
                                public void insertFill(MetaObject object) {
                                    setFieldValByName("id", ids.incrementAndGet(), object);
                                }

                                @Override
                                public void updateFill(MetaObject object) {}
                            }));
            for (var mapper : List.of(
                    SysNotifyRuleMapper.class,
                    SysNotifyRuleChannelMapper.class,
                    SysNotifyRuleRecipientMapper.class,
                    SysMailTemplateMapper.class,
                    SysNoticeMapper.class)) config.addMapper(mapper);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var channels = session.getMapper(SysNotifyRuleChannelMapper.class);
            var recipients = session.getMapper(SysNotifyRuleRecipientMapper.class);
            var templates = session.getMapper(SysMailTemplateMapper.class);
            var rules = new SysNotifyRuleServiceImpl(channels, recipients, templates, mock(SysSmsTemplateMapper.class));
            ReflectionTestUtils.setField(rules, "baseMapper", session.getMapper(SysNotifyRuleMapper.class));
            var ruleProxy = new ProxyFactory(rules);
            ruleProxy.setProxyTargetClass(true);
            ruleProxy.addAdvice(new TransactionInterceptor(
                    new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            rules = (SysNotifyRuleServiceImpl) ruleProxy.getProxy();
            var publisher = new NotifyPublisher(
                    rules,
                    channels,
                    recipients,
                    templates,
                    mock(SysSmsTemplateMapper.class),
                    mock(RoleMapper.class),
                    mock(UserRoleMapper.class),
                    mock(UserMapper.class),
                    mock(MailService.class),
                    mock(SmsService.class),
                    new InappServiceImpl(session.getMapper(SysNoticeMapper.class), templates),
                    mock(StringRedisTemplate.class));
            var proxy = new ProxyFactory(new NotifyRuleController(
                    rules, channels, recipients, templates, mock(SysSmsTemplateMapper.class), publisher));
            proxy.addAdvice(new TransactionInterceptor(
                    new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            var controller = (NotifyRuleController) proxy.getProxy();
            jdbc.execute(
                    "INSERT INTO sys_mail_template(id,template_code,scene,channel,subject,content) VALUES (10,'old','systemAlert','INAPP','Old','Old')");
            var body = new NotifyRuleSaveDto();
            body.setRuleName("Fixture");
            body.setEventCode("systemAlert");
            body.setRemark("old remark");
            var channel = new NotifyRuleSaveDto.ChannelDto();
            channel.setChannel("INAPP");
            channel.setTemplateId(10L);
            channel.setPlatform("web");
            var recipient = new NotifyRuleSaveDto.RecipientDto();
            recipient.setType("FIXED");
            recipient.setValue("41");
            channel.setRecipients(List.of(recipient));
            body.setChannels(List.of(channel));
            long id = controller.create(body).getData();
            channel.setTemplateId(null);
            channel.setPlatform(null);
            body.setRemark(null);
            controller.update(id, body);
            var saved = controller.getById(id).getData();
            assertNull(saved.getChannels().getFirst().getTemplateId());
            assertNull(saved.getChannels().getFirst().getPlatform());
            assertNull(saved.getRemark());
            jdbc.execute("DELETE FROM sys_mail_template WHERE id=10");
            jdbc.execute(
                    "INSERT INTO sys_mail_template(id,template_code,scene,channel,subject,content) VALUES (11,'automatic','systemAlert','INAPP','Automatic','Content')");
            assertTrue(
                    publisher.publish("systemAlert", Map.of(), null).getFirst().ok());
            assertEquals("Automatic", jdbc.queryForObject("SELECT title FROM sys_notice", String.class));
            assertEquals("all", jdbc.queryForObject("SELECT channel FROM sys_notice", String.class));
        }
    }
}
