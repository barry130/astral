package com.astral.system.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.dto.MessageTemplateSaveDto;
import com.astral.system.mail.MailServiceImpl;
import com.astral.system.mail.SysMailTemplateServiceImpl;
import com.astral.system.notify.MessageTemplateService;
import com.astral.system.notify.SmsServiceImpl;
import com.astral.system.notify.SysSmsTemplateServiceImpl;
import com.astral.system.notify.sms.SmsProvider;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.ObjectMapper;

/** Public template management and actual SMS service with an in-memory provider; no network delivery. */
class SmsTemplatePatchPersistenceTest {
    private JdbcTemplate jdbc;
    private SmsTemplateController controller;
    private SmsServiceImpl sms;
    private MessageTemplateService mergedTemplates;
    private TransactionTemplate transaction;
    private LocalValidatorFactoryBean validator;
    private long id;
    private final List<String> sentCodes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:sms_patch_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        var json = new ObjectMapper();
        for (String table : List.of("sys_sms_template", "sys_sms_provider", "sys_sms_log", "sys_mail_template")) {
            var metadata = json.readTree(
                    Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + table + ".json")));
            var columns = new ArrayList<String>();
            for (var field : metadata.path("fields")) {
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            }
            jdbc.execute("CREATE TABLE " + table + " (" + String.join(",", columns) + ")");
        }
        jdbc.execute("CREATE UNIQUE INDEX sms_code ON sys_sms_template(template_code)");
        jdbc.execute("CREATE UNIQUE INDEX sms_event ON sys_sms_template(event_code)");
        jdbc.update(
                "INSERT INTO sys_sms_provider(id,provider_name,provider_type,sign_name,enabled,weight) VALUES(1,'Fixture','FIXTURE','Default sign',1,1)");
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
                            public void insertFill(MetaObject row) {
                                if (getFieldValByName("id", row) == null)
                                    setFieldValByName("id", ids.incrementAndGet(), row);
                                setFieldValByName("createTime", LocalDateTime.now(), row);
                            }

                            @Override
                            public void updateFill(MetaObject row) {}
                        }));
        config.addMapper(SysSmsTemplateMapper.class);
        config.addMapper(SysSmsProviderMapper.class);
        config.addMapper(SysSmsLogMapper.class);
        config.addMapper(SysMailTemplateMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
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
                session.getMapper(SysSmsProviderMapper.class),
                session.getMapper(SysSmsTemplateMapper.class),
                session.getMapper(SysSmsLogMapper.class));
        var templates = new SysSmsTemplateServiceImpl(sms);
        ReflectionTestUtils.setField(templates, "baseMapper", session.getMapper(SysSmsTemplateMapper.class));
        var transactions = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(transactions);
        var proxy = new ProxyFactory(templates);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        var templateService = (SysSmsTemplateServiceImpl) proxy.getProxy();
        controller = new SmsTemplateController(templateService, sms);
        var mail = new MailServiceImpl();
        var mailTemplates = new SysMailTemplateServiceImpl(mail);
        ReflectionTestUtils.setField(mailTemplates, "baseMapper", session.getMapper(SysMailTemplateMapper.class));
        var mergedProxy =
                new ProxyFactory(new MessageTemplateService(mailTemplates, templateService, mail, sms, validator));
        mergedProxy.setProxyTargetClass(true);
        mergedProxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        mergedTemplates = (MessageTemplateService) mergedProxy.getProxy();
        var template = template("alert", "systemAlert", "PROVIDER_V1");
        controller.create(template);
        id = template.getId();
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
        validator.close();
    }

    @ParameterizedTest
    @MethodSource("blankRequiredFields")
    void partialUpdateRejectsBlankRequiredFieldsWithoutChangingTheSavedTemplate(
            String field, String value, String errorCode) {
        var before = controller.getById(id).getData();
        var patch = new SysSmsTemplate();
        switch (field) {
            case "templateCode" -> patch.setTemplateCode(value);
            case "templateName" -> patch.setTemplateName(value);
            case "eventCode" -> patch.setEventCode(value);
            case "providerTemplateCode" -> patch.setProviderTemplateCode(value);
            default -> throw new AssertionError("unknown fixture field");
        }
        assertEquals(
                errorCode,
                assertThrows(BusinessException.class, () -> controller.update(id, patch))
                        .getErrorCode());
        assertEquals(before, controller.getById(id).getData());
    }

    @ParameterizedTest
    @ValueSource(strings = {"update", "delete"})
    void aReadBeforeCommitCannotKeepAnObsoleteSmsMappingAfterCommit(String operation) {
        sms.sendSystemAlert("fixture-phone", "Title", "Content");
        transaction.executeWithoutResult(status -> {
            if ("update".equals(operation)) {
                var patch = new SysSmsTemplate();
                patch.setProviderTemplateCode("PROVIDER_V2");
                controller.update(id, patch);
            } else {
                controller.delete(id);
            }
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                // This caller has an independent connection and can only see the committed old row.
                executor.submit(() -> sms.sendSystemAlert("fixture-phone", "Title", "Content"))
                        .get(5, TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("independent SMS read failed", failure);
            }
        });
        if ("update".equals(operation)) {
            sms.sendSystemAlert("fixture-phone", "Title", "Content");
            assertEquals(List.of("PROVIDER_V1", "PROVIDER_V1", "PROVIDER_V2"), sentCodes);
        } else {
            assertThrows(IllegalStateException.class, () -> sms.sendSystemAlert("fixture-phone", "Title", "Content"));
            assertEquals(List.of("PROVIDER_V1", "PROVIDER_V1"), sentCodes);
        }
    }

    @Test
    void metadataPatchKeepsOmittedMappingAndIgnoresClientOwnedFields() {
        var before = controller.getById(id).getData();
        var patch = new SysSmsTemplate();
        patch.setTemplateName("Renamed");
        patch.setRemark("Updated remark");
        patch.setId(-1L);
        patch.setCreateTime(LocalDateTime.of(2001, 1, 1, 0, 0));
        patch.setUpdateTime(LocalDateTime.of(2001, 1, 1, 0, 0));
        controller.update(id, patch);
        var saved = controller.getById(id).getData();
        assertEquals("Renamed", saved.getTemplateName());
        assertEquals("Updated remark", saved.getRemark());
        assertEquals("alert", saved.getTemplateCode());
        assertEquals("systemAlert", saved.getEventCode());
        assertEquals("PROVIDER_V1", saved.getProviderTemplateCode());
        assertEquals(id, saved.getId());
        assertEquals(before.getCreateTime(), saved.getCreateTime());
        org.junit.jupiter.api.Assertions.assertNotEquals(patch.getUpdateTime(), saved.getUpdateTime());
    }

    @Test
    void legalEventAndProviderChangesRemainSendableAndOptionalFieldsCanBeCleared() {
        var patch = new SysSmsTemplate();
        patch.setTemplateCode("roles");
        patch.setTemplateName("Roles");
        patch.setEventCode("userRolesChanged");
        patch.setProviderTemplateCode("PROVIDER_V2");
        patch.setSignName("");
        patch.setContentSample("");
        patch.setAuditStatus(1);
        patch.setAuditRemark("");
        patch.setRemark("");
        controller.update(id, patch);
        var saved = controller.getById(id).getData();
        assertEquals("roles", saved.getTemplateCode());
        assertEquals("userRolesChanged", saved.getEventCode());
        assertEquals("", saved.getSignName());
        assertEquals("", saved.getContentSample());
        assertEquals(1, saved.getAuditStatus());
        var send = new SmsTemplateController.SmsTestSendDto();
        send.setPhone("fixture-phone");
        send.setVariables(
                Map.of("userId", "41", "addedRoles", "admin", "removedRoles", "", "changeSummary", "Updated"));
        controller.sendTest(id, send);
        assertEquals(List.of("PROVIDER_V2"), sentCodes);
    }

    @ParameterizedTest
    @MethodSource("invalidMappingChanges")
    void conflictsAndInvalidEventOrAuditStatusLeaveTheSavedMappingUnchanged(
            String field, String value, String errorCode) {
        controller.create(template("other", "userRolesChanged", "PROVIDER_OTHER"));
        var before = controller.getById(id).getData();
        var patch = new SysSmsTemplate();
        switch (field) {
            case "templateCode" -> patch.setTemplateCode(value);
            case "eventCode" -> patch.setEventCode(value);
            case "auditStatus" -> patch.setAuditStatus(Integer.valueOf(value));
            default -> throw new AssertionError("unknown fixture field");
        }
        assertEquals(
                errorCode,
                assertThrows(BusinessException.class, () -> controller.update(id, patch))
                        .getErrorCode());
        assertEquals(before, controller.getById(id).getData());
    }

    @Test
    void emptyPatchLeavesTheTemplateUnchanged() {
        var before = controller.getById(id).getData();
        controller.update(id, new SysSmsTemplate());
        assertEquals(before, controller.getById(id).getData());
    }

    @Test
    void missingTemplateCannotReportSuccessfulUpdate() {
        var patch = new SysSmsTemplate();
        patch.setTemplateName("Renamed");
        assertEquals(
                "SMS002",
                assertThrows(BusinessException.class, () -> controller.update(-1L, patch))
                        .getErrorCode());
        assertEquals("Alert", controller.getById(id).getData().getTemplateName());
    }

    @Test
    void omittedEventKeepsTheSmsTemplateCreatedByTheMergedTemplateInterface() {
        var prepared = new MessageTemplateSaveDto();
        prepared.setTemplateCode("prepared");
        prepared.setTemplateName("Prepared");
        prepared.setScene("changePasswordByEmail");
        prepared.setChannels(List.of("SMS"));
        prepared.setSmsProviderTemplateCode("PROVIDER_PREPARED");
        mergedTemplates.save(prepared);
        long preparedId = controller.page(1, 10).getData().getRecords().stream()
                .filter(t -> "prepared".equals(t.getTemplateCode()))
                .findFirst()
                .orElseThrow()
                .getId();
        var patch = new SysSmsTemplate();
        patch.setTemplateName("Renamed prepared");
        controller.update(preparedId, patch);
        var saved = controller.getById(preparedId).getData();
        assertEquals("Renamed prepared", saved.getTemplateName());
        assertEquals("changePasswordByEmail", saved.getEventCode());
        assertEquals("PROVIDER_PREPARED", saved.getProviderTemplateCode());
    }

    @Test
    void rollbackKeepsTheOriginalMappingAvailableToTheSmsCache() {
        var before = controller.getById(id).getData();
        sms.sendSystemAlert("fixture-phone", "Title", "Content");
        transaction.executeWithoutResult(status -> {
            var patch = new SysSmsTemplate();
            patch.setProviderTemplateCode("PROVIDER_V2");
            controller.update(id, patch);
            status.setRollbackOnly();
        });
        assertEquals(before, controller.getById(id).getData());
        sms.sendSystemAlert("fixture-phone", "Title", "Content");
        assertEquals(List.of("PROVIDER_V1", "PROVIDER_V1"), sentCodes);
    }

    private static Stream<Arguments> blankRequiredFields() {
        return Stream.of(
                Arguments.of("templateCode", "", "SMS013"),
                Arguments.of("templateCode", "  ", "SMS013"),
                Arguments.of("templateName", "", "SMS014"),
                Arguments.of("templateName", "  ", "SMS014"),
                Arguments.of("eventCode", "", "SMS014"),
                Arguments.of("eventCode", "  ", "SMS014"),
                Arguments.of("providerTemplateCode", "", "SMS015"),
                Arguments.of("providerTemplateCode", "  ", "SMS015"));
    }

    private static Stream<Arguments> invalidMappingChanges() {
        return Stream.of(
                Arguments.of("templateCode", "other", "SMS007"),
                Arguments.of("eventCode", "userRolesChanged", "SMS008"),
                Arguments.of("eventCode", "unknown-event", "MAIL009"),
                Arguments.of("auditStatus", "-1", "SMS016"),
                Arguments.of("auditStatus", "4", "SMS016"));
    }

    private SysSmsTemplate template(String code, String event, String providerCode) {
        var template = new SysSmsTemplate();
        template.setTemplateCode(code);
        template.setTemplateName("Alert");
        template.setEventCode(event);
        template.setProviderTemplateCode(providerCode);
        template.setAuditStatus(2);
        return template;
    }
}
