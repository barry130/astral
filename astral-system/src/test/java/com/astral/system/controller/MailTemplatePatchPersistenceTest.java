package com.astral.system.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.system.mail.MailServiceImpl;
import com.astral.system.mail.SysMailTemplateServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import tools.jackson.databind.json.JsonMapper;

class MailTemplatePatchPersistenceTest {
    private JdbcTemplate jdbc;
    private MailTemplateController controller;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:template_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        var definition = JsonMapper.builder()
                .build()
                .readTree(
                        Files.readString(Path.of("../astral-schema/src/main/resources/schema/sys_mail_template.json")));
        var columns = new ArrayList<String>();
        for (var field : definition.path("fields"))
            columns.add(field.path("columnName").asString() + " "
                    + field.path("jdbcType").asString()
                    + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
        jdbc.execute("CREATE TABLE sys_mail_template (" + String.join(",", columns) + ")");
        jdbc.execute("CREATE UNIQUE INDEX template_code_channel ON sys_mail_template(template_code,channel)");
        jdbc.execute("CREATE UNIQUE INDEX template_scene_channel ON sys_mail_template(scene,channel)");
        jdbc.update(
                "INSERT INTO sys_mail_template(id,template_code,template_name,scene,channel,subject,content) VALUES(1,'alert','Alert','systemAlert','EMAIL','${title}','${content}'),(2,'alert','Alert','systemAlert','INAPP','${title}','${content}')");
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(SysMailTemplateMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var templates = new SysMailTemplateServiceImpl(new MailServiceImpl());
        ReflectionTestUtils.setField(templates, "baseMapper", session.getMapper(SysMailTemplateMapper.class));
        var proxy = new ProxyFactory(templates);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        controller = new MailTemplateController((SysMailTemplateServiceImpl) proxy.getProxy(), new MailServiceImpl());
    }

    @AfterEach
    void close() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void contentOnlyPatchCannotSaveAnUnknownEventVariable() {
        var patch = new SysMailTemplate();
        patch.setContent("${unknown}");
        var rejected = assertThrows(BusinessException.class, () -> controller.update(1L, patch));
        assertEquals("MAIL022", rejected.getErrorCode());
        assertEquals("${content}", controller.getById(1L).getData().getContent());
    }

    @Test
    void subjectAndDeclaredVariablesAndSceneOnlyPatchesValidateRetainedContent() {
        var subject = new SysMailTemplate();
        subject.setSubject("${unknown}");
        assertEquals(
                "MAIL022",
                assertThrows(BusinessException.class, () -> controller.update(1L, subject))
                        .getErrorCode());
        var variables = new SysMailTemplate();
        variables.setVariables("[\"unknown\"]");
        assertEquals(
                "MAIL022",
                assertThrows(BusinessException.class, () -> controller.update(1L, variables))
                        .getErrorCode());
        var scene = new SysMailTemplate();
        scene.setScene("changePasswordByEmail");
        assertEquals(
                "MAIL022",
                assertThrows(BusinessException.class, () -> controller.update(1L, scene))
                        .getErrorCode());
        assertEquals("systemAlert", controller.getById(1L).getData().getScene());
    }

    @Test
    void partialCodeAndSceneUpdatesKeepLegalSiblingChannelsAndRejectRealCollisions() {
        var code = new SysMailTemplate();
        code.setTemplateCode("alert");
        code.setTemplateName("Renamed");
        controller.update(1L, code);
        var scene = new SysMailTemplate();
        scene.setScene("systemAlert");
        controller.update(1L, scene);
        assertEquals("Renamed", controller.getById(1L).getData().getTemplateName());
        assertEquals("EMAIL", controller.getById(1L).getData().getChannel());
        var conflict = new SysMailTemplate();
        conflict.setChannel("INAPP");
        assertEquals(
                "MAIL008",
                assertThrows(BusinessException.class, () -> controller.update(1L, conflict))
                        .getErrorCode());
        var valid = new SysMailTemplate();
        valid.setContent("Updated ${title}");
        valid.setVariables("[]");
        controller.update(1L, valid);
        assertEquals("[\"title\"]", controller.getById(1L).getData().getVariables());
        assertEquals("${content}", controller.getById(2L).getData().getContent());
    }
}
