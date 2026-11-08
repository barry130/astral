package com.astral.system.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.dto.MessageTemplateSaveDto;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailTemplateServiceImpl;
import com.astral.system.notify.sms.SmsProvider;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** Real service calls and MyBatis persistence; no application datasource or network is used. */
class MessageTemplatePersistenceTest {
    private Connection keepAlive;
    private MessageTemplateService service;
    private SysMailTemplateServiceImpl mailTemplates;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private SmsServiceImpl sms;
    private final List<String> sentCodes = new CopyOnWriteArrayList<>();
    private final BeforeWrite writes = new BeforeWrite();

    @BeforeEach
    void setUp() throws SQLException {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:templates_" + UUID.randomUUID() + ";MODE=PostgreSQL");
        keepAlive = source.getConnection();
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE sys_mail_template (
                  id BIGINT PRIMARY KEY, template_code VARCHAR(64), template_name VARCHAR(128),
                  subject VARCHAR(256), content TEXT, variables VARCHAR(512), scene VARCHAR(64),
                  channel VARCHAR(16), remark VARCHAR(256), create_time TIMESTAMP, update_time TIMESTAMP,
                  UNIQUE(template_code, channel), UNIQUE(scene, channel))
                """);
        jdbc.execute("""
                CREATE TABLE sys_sms_template (
                  id BIGINT PRIMARY KEY, template_code VARCHAR(64) UNIQUE, template_name VARCHAR(64),
                  event_code VARCHAR(64) UNIQUE, provider_template_code VARCHAR(64), sign_name VARCHAR(64),
                  content_sample TEXT, audit_status INTEGER, audit_remark VARCHAR(256),
                  remark VARCHAR(256), create_time TIMESTAMP, update_time TIMESTAMP)
                """);
        MybatisConfiguration config = new MybatisConfiguration();
        config.setEnvironment(new Environment("test", new SpringManagedTransactionFactory(), source));
        config.addInterceptor(writes);
        AtomicLong ids = new AtomicLong(1000);
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
        config.addMapper(SysMailTemplateMapper.class);
        config.addMapper(SysSmsTemplateMapper.class);
        SqlSessionTemplate session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        mailTemplates = new SysMailTemplateServiceImpl();
        ReflectionTestUtils.setField(mailTemplates, "baseMapper", session.getMapper(SysMailTemplateMapper.class));
        SysSmsTemplateServiceImpl smsTemplates = new SysSmsTemplateServiceImpl();
        ReflectionTestUtils.setField(smsTemplates, "baseMapper", session.getMapper(SysSmsTemplateMapper.class));
        SysSmsProvider provider = new SysSmsProvider();
        provider.setId(1L);
        provider.setProviderType("FIXTURE");
        SysSmsProviderMapper providers = mock(SysSmsProviderMapper.class);
        when(providers.selectList(any())).thenReturn(List.of(provider));
        SmsProvider delivery = new SmsProvider() {
            @Override
            public String type() {
                return "FIXTURE";
            }

            @Override
            public void send(
                    SysSmsProvider selected, String phone, String sign, String code, Map<String, String> params) {
                sentCodes.add(code);
            }
        };
        sms = new SmsServiceImpl(
                List.of(delivery),
                providers,
                session.getMapper(SysSmsTemplateMapper.class),
                mock(SysSmsLogMapper.class));
        DataSourceTransactionManager transactions = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(transactions);
        ProxyFactory proxy =
                new ProxyFactory(new MessageTemplateService(mailTemplates, smsTemplates, mock(MailService.class), sms));
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        service = (MessageTemplateService) proxy.getProxy();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (keepAlive != null) {
            keepAlive.close();
        }
    }

    @Test
    void rebindingEventAndCodeKeepsChannelContentAndReferencedIds() {
        service.save(body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE));
        List<Long> ids =
                mailTemplates.list().stream().map(SysMailTemplate::getId).toList();
        MessageTemplateSaveDto changed = body("alert", NotifyEventRegistry.SYSTEM_ALERT);
        changed.setOriginalCode("password");
        changed.setOriginalScene(NotifyEventRegistry.QT_PASSWORD_RESET_CODE);
        changed.setContent("Updated content");

        service.save(changed);

        var rows = service.list();
        assertEquals(1, rows.size());
        assertEquals("alert", rows.getFirst().getTemplateCode());
        assertEquals(NotifyEventRegistry.SYSTEM_ALERT, rows.getFirst().getScene());
        assertEquals(List.of("EMAIL", "INAPP"), rows.getFirst().getChannels());
        assertEquals("Updated content", rows.getFirst().getContent());
        for (Long id : ids) {
            assertNotNull(mailTemplates.getById(id), "explicit template references must remain valid");
        }
    }

    private static MessageTemplateSaveDto body(String code, String scene) {
        MessageTemplateSaveDto dto = new MessageTemplateSaveDto();
        dto.setTemplateCode(code);
        dto.setTemplateName("Fixture template");
        dto.setScene(scene);
        dto.setChannels(List.of("EMAIL", "INAPP"));
        dto.setSubject("Fixture subject");
        dto.setContent("Fixture content");
        return dto;
    }

    @Test
    void renamingCodeDoesNotConflictWithItsOwnEventBinding() {
        service.save(body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE));
        MessageTemplateSaveDto changed = body("renamed", NotifyEventRegistry.QT_PASSWORD_RESET_CODE);
        changed.setOriginalCode("password");

        service.save(changed);

        assertEquals("renamed", service.list().getFirst().getTemplateCode());
        assertEquals(List.of("EMAIL", "INAPP"), service.list().getFirst().getChannels());
    }

    @Test
    void failedSecondChannelRollsBackFirstChannelChange() {
        service.save(body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE));
        jdbc.execute("ALTER TABLE sys_mail_template ADD CHECK (channel <> 'INAPP' OR subject <> 'reject')");
        MessageTemplateSaveDto changed = body("renamed", NotifyEventRegistry.QT_PASSWORD_RESET_CODE);
        changed.setOriginalCode("password");
        changed.setSubject("reject");

        assertThrows(DataIntegrityViolationException.class, () -> service.save(changed));

        assertEquals(1, service.list().size());
        assertEquals("password", service.list().getFirst().getTemplateCode());
        assertEquals("Fixture subject", service.list().getFirst().getSubject());
        assertEquals(List.of("EMAIL", "INAPP"), service.list().getFirst().getChannels());
    }

    @Test
    void deselectingChannelRemovesOnlyThatChannel() {
        service.save(body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE));
        MessageTemplateSaveDto changed = body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE);
        changed.setChannels(List.of("INAPP"));

        service.save(changed);

        assertEquals(1, service.list().size());
        assertEquals(List.of("INAPP"), service.list().getFirst().getChannels());
        assertEquals("Fixture content", service.list().getFirst().getContent());
    }

    @Test
    void committedTemplateReplacesVersionCachedByConcurrentDelivery() {
        MessageTemplateSaveDto dto = body("alert", NotifyEventRegistry.SYSTEM_ALERT);
        dto.setChannels(List.of("SMS"));
        dto.setSmsProviderTemplateCode("OLD");
        service.save(dto);
        dto.setSmsProviderTemplateCode("NEW");

        transaction.executeWithoutResult(status -> {
            service.save(dto);
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> sms.sendSystemAlert("fixture", "title", "content"))
                        .get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        sms.sendSystemAlert("fixture", "title", "content");

        assertEquals(List.of("OLD", "NEW"), sentCodes);
    }

    @Test
    void concurrentChannelRemovalCannotSilentlyCommitPartialSave() {
        service.save(body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE));
        MessageTemplateSaveDto changed = body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE);
        changed.setSubject("New subject");
        writes.action.set(() -> {
            MessageTemplateSaveDto concurrent = body("password", NotifyEventRegistry.QT_PASSWORD_RESET_CODE);
            concurrent.setChannels(List.of("EMAIL"));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> service.save(concurrent)).get();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThrows(BusinessException.class, () -> service.save(changed));

        assertEquals(List.of("EMAIL"), service.list().getFirst().getChannels());
        assertEquals("Fixture subject", service.list().getFirst().getSubject());
    }

    /** Controls database scheduling; all reads, writes and affected-row counts still use real MyBatis SQL. */
    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static final class BeforeWrite implements Interceptor {
        final AtomicReference<Runnable> action = new AtomicReference<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Runnable pending = action.getAndSet(null);
            if (pending != null) {
                pending.run();
            }
            return invocation.proceed();
        }
    }
}
