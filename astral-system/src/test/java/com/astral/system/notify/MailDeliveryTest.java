package com.astral.system.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailAccount;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.mapper.SysMailAccountMapper;
import com.astral.dao.mapper.SysMailLogMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.system.mail.MailServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

class MailDeliveryTest {
    @Test
    void maximumWeightsKeepMailFailoverFiniteAndTryEachAccountOnce() {
        var service = new MailServiceImpl();
        var templates = mock(SysMailTemplateMapper.class);
        var template = new SysMailTemplate();
        template.setSubject("Fixture");
        template.setContent("Fixture");
        when(templates.selectById(1L)).thenReturn(template);
        ReflectionTestUtils.setField(service, "templateMapper", templates);
        ReflectionTestUtils.setField(service, "logMapper", mock(SysMailLogMapper.class));
        var accounts = mock(SysMailAccountMapper.class);
        var candidates = new ArrayList<SysMailAccount>();
        for (long id = 1; id <= 3; id++) {
            var account = new SysMailAccount();
            account.setId(id);
            account.setWeight(Integer.MAX_VALUE);
            account.setSmtpHost("fixture-" + id);
            account.setSmtpPort(25);
            account.setFromAddr("fixture@example.test");
            candidates.add(account);
        }
        when(accounts.selectList(any())).thenReturn(candidates);
        ReflectionTestUtils.setField(service, "accountMapper", accounts);
        var attempted = new ArrayList<String>();
        try (var smtp = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            doAnswer(call -> {
                        attempted.add(call.getArgument(0));
                        return null;
                    })
                    .when(sender)
                    .setHost(any());
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
            org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("fixture failure"))
                    .when(sender)
                    .send(any(MimeMessage.class));
        })) {
            assertTimeout(
                    java.time.Duration.ofSeconds(3),
                    () -> assertThrows(
                            BusinessException.class,
                            () -> service.testSendTemplate(1L, null, "receiver@example.test", java.util.Map.of())));
            assertEquals(3, attempted.size());
            assertEquals(3, attempted.stream().distinct().count());
        }
    }

    @Test
    void legacyTemplateCodeOnlySelectsEmailChannel() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:mail_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        try (var keepAlive = source.getConnection()) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                    CREATE TABLE sys_mail_template(id BIGINT PRIMARY KEY, template_code VARCHAR, template_name VARCHAR,
                    subject VARCHAR, content TEXT, variables VARCHAR, scene VARCHAR, channel VARCHAR, remark VARCHAR,
                    create_time TIMESTAMP, update_time TIMESTAMP)
                    """);
            jdbc.execute("""
                    INSERT INTO sys_mail_template(id,template_code,channel,subject,content) VALUES
                    (1,'systemAlert','EMAIL','Email only','Email body'),(2,'systemAlert','INAPP','Private inapp','Private content')
                    """);
            var config = new MybatisConfiguration();
            config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
            config.setMapUnderscoreToCamelCase(true);
            config.addMapper(SysMailTemplateMapper.class);
            var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
            var service = new MailServiceImpl();
            ReflectionTestUtils.setField(service, "templateMapper", session.getMapper(SysMailTemplateMapper.class));
            ReflectionTestUtils.setField(service, "logMapper", mock(SysMailLogMapper.class));
            var accounts = mock(SysMailAccountMapper.class);
            var account = new SysMailAccount();
            account.setId(1L);
            account.setFromAddr("fixture@example.test");
            account.setSmtpPort(25);
            when(accounts.selectList(any())).thenReturn(List.of(account));
            ReflectionTestUtils.setField(service, "accountMapper", accounts);
            var subjects = new ArrayList<String>();
            try (var smtp = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
                when(sender.createMimeMessage())
                        .thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
                doAnswer(call -> {
                            subjects.add(call.getArgument(0, MimeMessage.class).getSubject());
                            return null;
                        })
                        .when(sender)
                        .send(any(MimeMessage.class));
            })) {
                service.sendSystemAlert("receiver@example.test", "Fallback", "Fallback body");
                assertEquals(List.of("Email only"), subjects);
                jdbc.execute("DELETE FROM sys_mail_template WHERE id=1");
                service.evictTemplateCache();
                service.sendSystemAlert("receiver@example.test", "Fallback", "Fallback body");
                assertEquals(List.of("Email only", "Fallback"), subjects);
            }
        }
    }
}
