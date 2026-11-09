package com.astral.system.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.astral.auth.security.PermissionCache;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.mapper.DictDataMapper;
import com.astral.dao.mapper.DictTypeMapper;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.SysConfigMapper;
import com.astral.dao.mapper.SysMailAccountMapper;
import com.astral.dao.mapper.SysMenuMapper;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.system.mail.MailServiceImpl;
import com.astral.system.mail.SysMailAccountServiceImpl;
import com.astral.system.notify.SmsServiceImpl;
import com.astral.system.notify.SysSmsProviderServiceImpl;
import com.astral.system.notify.sms.MockSmsProvider;
import com.astral.system.notify.sms.SmsProvider;
import com.astral.system.service.impl.DictDataServiceImpl;
import com.astral.system.service.impl.DictTypeServiceImpl;
import com.astral.system.service.impl.PermissionServiceImpl;
import com.astral.system.service.impl.RoleServiceImpl;
import com.astral.system.service.impl.SysConfigServiceImpl;
import com.astral.system.service.impl.SysMenuServiceImpl;
import com.astral.system.service.impl.UserServiceImpl;
import com.astral.system.validation.EntityPatchValidator;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import jakarta.servlet.ServletException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;
import tools.jackson.databind.json.JsonMapper;

/** Actual public binding and persistence; standalone MVC does not include the application's auth/advice. */
class AdminWriteValidationTest {
    private JdbcTemplate jdbc;
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    private RoleController roles;
    private PermissionController permissions;
    private DictController legacyDict;
    private DictDataController dictData;
    private DictTypeController dictTypes;
    private UserController users;
    private SysMenuController menus;
    private SysConfigController configs;
    private MailAccountController mailAccounts;
    private SmsProviderController smsProviders;
    private SmsServiceImpl sms;
    private final List<Long> deliveredProviders = new ArrayList<>();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setUp() throws Exception {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:admin_input_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        String initial =
                Files.readString(Path.of("../astral-server/src/main/resources/db/migration/V20260914001__init.sql"));
        for (String name : List.of(
                "sys_role",
                "sys_permission",
                "sys_dict_data",
                "sys_dict_type",
                "sys_user",
                "sys_menu",
                "sys_config",
                "sys_mail_account")) {
            var table = Pattern.compile("CREATE TABLE IF NOT EXISTS " + name + " \\(.*?\\);", Pattern.DOTALL)
                    .matcher(initial);
            if (!table.find()) fail("Actual table DDL not found: " + name);
            jdbc.execute(table.group());
        }
        String smsMigration = Files.readString(
                Path.of("../astral-server/src/main/resources/db/migration/V20261002003__sms_channel.sql"));
        for (String name : List.of("sys_sms_provider", "sys_sms_template", "sys_sms_log")) {
            var smsTable = Pattern.compile("CREATE TABLE IF NOT EXISTS " + name + " \\(.*?\\);", Pattern.DOTALL)
                    .matcher(smsMigration);
            if (!smsTable.find()) fail("Actual SMS DDL not found: " + name);
            jdbc.execute(smsTable.group());
        }
        jdbc.execute("ALTER TABLE sys_role ADD COLUMN is_super SMALLINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE sys_permission ADD COLUMN domain VARCHAR(32)");
        jdbc.execute("ALTER TABLE sys_user ADD COLUMN user_type VARCHAR(20) NOT NULL DEFAULT 'ADMIN'");
        jdbc.execute("ALTER TABLE sys_user ADD COLUMN device_id VARCHAR(128)");
        jdbc.execute("ALTER TABLE sys_user ADD COLUMN totp_secret VARCHAR(128)");
        jdbc.execute("ALTER TABLE sys_user ADD COLUMN totp_enabled SMALLINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE sys_user ADD COLUMN must_change_password SMALLINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE sys_user ADD COLUMN status_reason VARCHAR(255)");
        jdbc.update("INSERT INTO sys_role(id,role_code,role_name) VALUES(1,'original','Original')");
        jdbc.update(
                "INSERT INTO sys_dict_type(id,dict_code,dict_name,description) VALUES(1,'original','Original','Keep')");
        jdbc.update(
                "INSERT INTO sys_dict_data(id,dict_type_id,dict_label,dict_value,description) VALUES(1,1,'Original','original','Keep')");
        jdbc.update(
                "INSERT INTO sys_user(id,username,password,nickname) VALUES(1,'original','fixture-hash','Original')");
        jdbc.update("INSERT INTO sys_menu(id,name) VALUES(1,'Original')");
        jdbc.update(
                "INSERT INTO sys_config(id,config_name,config_key,config_value) VALUES(1,'Original','original','Keep')");
        jdbc.update(
                "INSERT INTO sys_mail_account(id,account_name,smtp_host,smtp_port,username,password,from_addr) VALUES(1,'Original','smtp.invalid',587,'fixture','fixture-secret','fixture@example.invalid')");
        jdbc.update(
                "INSERT INTO sys_sms_provider(id,provider_name,provider_type,access_secret) VALUES(1,'Original','MOCK','fixture-secret')");
        jdbc.update(
                "INSERT INTO sys_sms_template(id,template_code,template_name,event_code,provider_template_code) VALUES(1,'fixture','Fixture','systemAlert','PROVIDER_V1')");
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
                                setFieldValByName("updateTime", LocalDateTime.now(), row);
                            }

                            @Override
                            public void updateFill(MetaObject row) {
                                setFieldValByName("updateTime", LocalDateTime.now(), row);
                            }
                        }));
        config.addMapper(RoleMapper.class);
        config.addMapper(PermissionMapper.class);
        config.addMapper(DictDataMapper.class);
        config.addMapper(DictTypeMapper.class);
        config.addMapper(UserMapper.class);
        config.addMapper(SysMenuMapper.class);
        config.addMapper(SysConfigMapper.class);
        config.addMapper(SysMailAccountMapper.class);
        config.addMapper(SysSmsProviderMapper.class);
        config.addMapper(SysSmsTemplateMapper.class);
        config.addMapper(SysSmsLogMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var roleService = new RoleServiceImpl(null, null);
        ReflectionTestUtils.setField(roleService, "baseMapper", session.getMapper(RoleMapper.class));
        roles = new RoleController(
                roleService, null, null, null, new PermissionCache(), new EntityPatchValidator(validator));
        var permissionService = new PermissionServiceImpl(null);
        ReflectionTestUtils.setField(permissionService, "baseMapper", session.getMapper(PermissionMapper.class));
        permissions = new PermissionController(permissionService, new PermissionCache());
        var dataService = new DictDataServiceImpl();
        ReflectionTestUtils.setField(dataService, "baseMapper", session.getMapper(DictDataMapper.class));
        var typeService = new DictTypeServiceImpl();
        ReflectionTestUtils.setField(typeService, "baseMapper", session.getMapper(DictTypeMapper.class));
        legacyDict = new DictController(dataService, new EntityPatchValidator(validator));
        dictData = new DictDataController(dataService, session.getMapper(DictDataMapper.class));
        dictTypes = new DictTypeController(typeService);
        var userService = new UserServiceImpl();
        ReflectionTestUtils.setField(userService, "baseMapper", session.getMapper(UserMapper.class));
        users = new UserController(userService, null, null, new PermissionCache(), new EntityPatchValidator(validator));
        menus = new SysMenuController(
                new SysMenuServiceImpl(session.getMapper(SysMenuMapper.class)), new EntityPatchValidator(validator));
        var configService = new SysConfigServiceImpl();
        ReflectionTestUtils.setField(configService, "baseMapper", session.getMapper(SysConfigMapper.class));
        configService.init();
        configs = new SysConfigController(configService, new EntityPatchValidator(validator));
        var accountService = new SysMailAccountServiceImpl();
        ReflectionTestUtils.setField(accountService, "baseMapper", session.getMapper(SysMailAccountMapper.class));
        mailAccounts =
                new MailAccountController(accountService, new MailServiceImpl(), new EntityPatchValidator(validator));
        var providerService = new SysSmsProviderServiceImpl();
        ReflectionTestUtils.setField(providerService, "baseMapper", session.getMapper(SysSmsProviderMapper.class));
        SmsProvider delivery = new SmsProvider() {
            @Override
            public String type() {
                return "FIXTURE";
            }

            @Override
            public void send(
                    SysSmsProvider provider, String phone, String sign, String code, Map<String, String> variables) {
                deliveredProviders.add(provider.getId());
            }
        };
        List<SmsProvider> implementations = List.of(new MockSmsProvider(), delivery);
        sms = new SmsServiceImpl(
                implementations,
                session.getMapper(SysSmsProviderMapper.class),
                session.getMapper(SysSmsTemplateMapper.class),
                session.getMapper(SysSmsLogMapper.class));
        smsProviders = new SmsProviderController(providerService, sms, implementations);
        mvc = MockMvcBuilders.standaloneSetup(
                        roles,
                        permissions,
                        legacyDict,
                        dictData,
                        dictTypes,
                        users,
                        menus,
                        configs,
                        mailAccounts,
                        smsProviders)
                .setValidator(validator)
                .build();
    }

    @AfterEach
    void close() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
        if (validator != null) validator.close();
    }

    @Test
    void roleCreationRejectsOversizedNamesBeforeWritingAndAcceptsTheDatabaseBoundary() throws Exception {
        reject(post("/api/v1/admin/system/role"), Map.of("roleCode", "new", "roleName", "N".repeat(129)));
        assertEquals(1, roles.getAll().getData().size());
        accept(post("/api/v1/admin/system/role"), Map.of("roleCode", "new", "roleName", "N".repeat(128)));
        assertEquals("N".repeat(128), roles.getById(1001L).getData().getRoleName());
    }

    @Test
    void rolePatchChecksProvidedMetadataWithoutRequiringTheImmutableCode() throws Exception {
        reject(put("/api/v1/admin/system/role/1"), Map.of("roleName", "N".repeat(129)));
        assertEquals("Original", roles.getById(1L).getData().getRoleName());
        accept(put("/api/v1/admin/system/role/1"), Map.of("roleName", "N".repeat(128), "isSuper", 1));
        assertEquals("N".repeat(128), roles.getById(1L).getData().getRoleName());
        assertEquals("original", roles.getById(1L).getData().getRoleCode());
        assertEquals(0, roles.getById(1L).getData().getIsSuper());
        accept(put("/api/v1/admin/system/role/1"), Map.of("sort", 5));
        assertEquals(5, roles.getById(1L).getData().getSort());
        assertEquals("N".repeat(128), roles.getById(1L).getData().getRoleName());
    }

    @Test
    void dictionaryWritesRejectInvalidInputAndRetainFullFormVersusPartialSemantics() throws Exception {
        for (String path : List.of("/api/v1/admin/system/dict", "/api/v1/admin/system/dict/data")) {
            reject(post(path), Map.of("dictTypeId", 1, "dictLabel", "N".repeat(129), "dictValue", "new"));
            reject(post(path), Map.of("dictTypeId", 1, "dictValue", "new"));
            reject(put(path + "/1"), Map.of("dictTypeId", 1, "dictLabel", "N".repeat(129), "dictValue", "new"));
            assertEquals("Original", dictData.getById(1L).getData().getDictLabel());
        }
        reject(post("/api/v1/admin/system/dict/type"), Map.of("dictCode", "new", "dictName", "N".repeat(129)));
        reject(put("/api/v1/admin/system/dict/type/1"), Map.of("dictCode", "original"));
        assertEquals("Original", dictTypes.getById(1L).getData().getDictName());
        accept(put("/api/v1/admin/system/dict/1"), Map.of("dictLabel", "N".repeat(128)));
        assertEquals("Keep", legacyDict.getById(1L).getData().getDescription());
        accept(
                put("/api/v1/admin/system/dict/data/1"),
                Map.of("dictTypeId", 1, "dictLabel", "New", "dictValue", "new"));
        assertNull(dictData.getById(1L).getData().getDescription());
        accept(put("/api/v1/admin/system/dict/type/1"), Map.of("dictCode", "original", "dictName", "New"));
        assertNull(dictTypes.getById(1L).getData().getDescription());
    }

    @Test
    void userProfilePatchRejectsOversizedValuesWithoutRequiringOrWritingCredentials() throws Exception {
        reject(put("/api/v1/admin/system/user/1"), Map.of("nickname", "N".repeat(65)));
        assertEquals("Original", users.getById(1L).getData().getNickname());
        accept(
                put("/api/v1/admin/system/user/1"),
                Map.of("nickname", "N".repeat(64), "userType", "APP", "password", "P".repeat(200)));
        assertEquals("N".repeat(64), users.getById(1L).getData().getNickname());
        assertEquals("original", users.getById(1L).getData().getUsername());
        assertEquals("ADMIN", users.getById(1L).getData().getUserType());
    }

    @Test
    void menuWritesValidateNamesWhileDragReorderingRemainsAPartialWrite() throws Exception {
        reject(post("/api/v1/admin/system/menu"), Map.of("name", "N".repeat(65)));
        reject(post("/api/v1/admin/system/menu"), Map.of("sort", 3));
        reject(put("/api/v1/admin/system/menu/1"), Map.of("name", "N".repeat(65)));
        assertEquals("Original", menus.getById(1L).getData().getName());
        accept(put("/api/v1/admin/system/menu/1"), Map.of("sort", 3, "parentId", 0));
        assertEquals("Original", menus.getById(1L).getData().getName());
        assertEquals(3, menus.getById(1L).getData().getSort());
        accept(post("/api/v1/admin/system/menu"), Map.of("name", "N".repeat(64)));
        assertEquals(2, menus.getMenuList().getData().size());
    }

    @Test
    void configurationWritesRejectOversizedNamesAndPreserveLongTextValues() throws Exception {
        reject(
                post("/api/v1/admin/system/config"),
                Map.of("configName", "N".repeat(129), "configKey", "new", "configValue", "value"));
        reject(put("/api/v1/admin/system/config/1"), Map.of("configName", "N".repeat(129)));
        assertEquals("Original", configs.getById(1L).getData().getConfigName());
        accept(
                post("/api/v1/admin/system/config"),
                Map.of("configName", "N".repeat(128), "configKey", "new", "configValue", "V".repeat(2000)));
        assertEquals("V".repeat(2000), configs.getById(1001L).getData().getConfigValue());
        accept(put("/api/v1/admin/system/config/1"), Map.of("configValue", "V".repeat(3000)));
        assertEquals("V".repeat(3000), configs.getById(1L).getData().getConfigValue());
        assertEquals("Original", configs.getById(1L).getData().getConfigName());
    }

    @Test
    void mailAccountWritesValidateRequiredCreationFieldsAndOptionalPatchValues() throws Exception {
        reject(post("/api/v1/admin/system/mail/account"), Map.of("accountName", "New"));
        reject(put("/api/v1/admin/system/mail/account/1"), Map.of("accountName", "N".repeat(65)));
        assertEquals("Original", mailAccounts.getById(1L).getData().getAccountName());
        accept(put("/api/v1/admin/system/mail/account/1"), Map.of("accountName", "N".repeat(64)));
        assertEquals("N".repeat(64), mailAccounts.getById(1L).getData().getAccountName());
        assertEquals("smtp.invalid", mailAccounts.getById(1L).getData().getSmtpHost());
        assertNull(mailAccounts.getById(1L).getData().getPassword());
        accept(
                post("/api/v1/admin/system/mail/account"),
                Map.of(
                        "accountName",
                        "New",
                        "smtpHost",
                        "smtp.invalid",
                        "smtpPort",
                        587,
                        "username",
                        "fixture",
                        "password",
                        "fixture-secret",
                        "fromAddr",
                        "fixture@example.invalid"));
        assertEquals(2, mailAccounts.page(1, 10).getData().getRecords().size());
    }

    @Test
    void smsProviderWritesValidateMetadataWithoutRequiringUnchangedCredentials() throws Exception {
        reject(
                post("/api/v1/admin/system/sms/provider"),
                Map.of("providerName", "N".repeat(65), "providerType", "MOCK"));
        reject(
                put("/api/v1/admin/system/sms/provider/1"),
                Map.of("providerName", "N".repeat(65), "providerType", "MOCK"));
        assertEquals("Original", smsProviders.getById(1L).getData().getProviderName());
        accept(
                put("/api/v1/admin/system/sms/provider/1"),
                Map.of("providerName", "N".repeat(64), "providerType", "MOCK"));
        assertEquals("N".repeat(64), smsProviders.getById(1L).getData().getProviderName());
        assertNull(smsProviders.getById(1L).getData().getAccessSecret());
        accept(post("/api/v1/admin/system/sms/provider"), Map.of("providerName", "New", "providerType", "MOCK"));
        assertEquals(2, smsProviders.page(1, 10).getData().getRecords().size());
    }

    @Test
    void smsProviderPatchesToggleWithoutResendingIdentityAndStillRejectInvalidFields() throws Exception {
        accept(put("/api/v1/admin/system/sms/provider/1"), Map.of("enabled", 0));
        assertEquals(0, smsProviders.getById(1L).getData().getEnabled());
        assertEquals("Original", smsProviders.getById(1L).getData().getProviderName());
        assertEquals("MOCK", smsProviders.getById(1L).getData().getProviderType());
        accept(put("/api/v1/admin/system/sms/provider/1"), Map.of("enabled", 1));
        assertEquals(1, smsProviders.getById(1L).getData().getEnabled());
        accept(put("/api/v1/admin/system/sms/provider/1"), Map.of("providerName", "Renamed"));
        accept(put("/api/v1/admin/system/sms/provider/1"), Map.of("providerType", "MOCK"));
        rejectBusiness(put("/api/v1/admin/system/sms/provider/1"), Map.of("providerName", " "), "SMS012");
        rejectBusiness(put("/api/v1/admin/system/sms/provider/1"), Map.of("providerType", " "), "SMS011");
        rejectBusiness(put("/api/v1/admin/system/sms/provider/1"), Map.of("providerType", "UNKNOWN"), "SMS006");
        rejectBusiness(put("/api/v1/admin/system/sms/provider/1"), Map.of("accessSecret", " "), "MAIL007");
        accept(post("/api/v1/admin/system/sms/provider"), Map.of("providerName", "Other", "providerType", "MOCK"));
        rejectBusiness(put("/api/v1/admin/system/sms/provider/1"), Map.of("providerName", "Other"), "SMS010");
        rejectBusiness(post("/api/v1/admin/system/sms/provider"), Map.of("enabled", 1), "SMS012");
        rejectBusiness(post("/api/v1/admin/system/sms/provider"), Map.of("providerName", "Missing type"), "SMS011");
        rejectBusiness(put("/api/v1/admin/system/sms/provider/999"), Map.of("enabled", 0), "SMS001");
        assertEquals("Renamed", smsProviders.getById(1L).getData().getProviderName());
        assertEquals("MOCK", smsProviders.getById(1L).getData().getProviderType());
        assertEquals(1, smsProviders.getById(1L).getData().getEnabled());
        assertNull(smsProviders.getById(1L).getData().getAccessSecret());
        assertEquals(2, smsProviders.page(1, 10).getData().getRecords().size());
    }

    @Test
    void acceptedSmsProviderTypesPersistCanonicallyAndResolveForDelivery() throws Exception {
        accept(
                post("/api/v1/admin/system/sms/provider"),
                Map.of("providerName", "Padded type", "providerType", " fIxTuRe "));
        assertEquals("FIXTURE", smsProviders.getById(1001L).getData().getProviderType());
        sms.testSendTemplate(1L, 1001L, "fixture-phone", Map.of());
        accept(put("/api/v1/admin/system/sms/provider/1"), Map.of("providerType", " fixture "));
        assertEquals("FIXTURE", smsProviders.getById(1L).getData().getProviderType());
        sms.testSendTemplate(1L, 1L, "fixture-phone", Map.of());
        assertEquals(List.of(1001L, 1L), deliveredProviders);
    }

    @Test
    void legacySmsProviderTypesResolveWithoutDependingOnTheDefaultLocale() {
        jdbc.update(
                "INSERT INTO sys_sms_provider(id,provider_name,provider_type) VALUES(2,'Legacy padded type',' fixture ')");
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            sms.testSendTemplate(1L, 2L, "fixture-phone", Map.of());
            assertEquals(List.of(2L), deliveredProviders);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void dictionaryDescriptionsAcceptTheFullDatabaseWidth() throws Exception {
        accept(
                put("/api/v1/admin/system/dict/type/1"),
                Map.of("dictCode", "original", "dictName", "Original", "description", "D".repeat(1024)));
        assertEquals("D".repeat(1024), dictTypes.getById(1L).getData().getDescription());
        reject(
                put("/api/v1/admin/system/dict/type/1"),
                Map.of("dictCode", "original", "dictName", "Original", "description", "D".repeat(1025)));
        assertEquals("D".repeat(1024), dictTypes.getById(1L).getData().getDescription());
    }

    @Test
    void phoneNumbersAcceptTheFullDatabaseWidthAndRejectOverflow() throws Exception {
        accept(put("/api/v1/admin/system/user/1"), Map.of("phone", "1".repeat(32)));
        assertEquals("1".repeat(32), users.getById(1L).getData().getPhone());
        reject(put("/api/v1/admin/system/user/1"), Map.of("phone", "1".repeat(33)));
        assertEquals("1".repeat(32), users.getById(1L).getData().getPhone());
    }

    @Test
    void permissionCodesAcceptTheDatabaseBoundaryOnCreateAndUpdate() throws Exception {
        String code = "admin:system:resource:view:" + "a".repeat(101);
        accept(post("/api/v1/admin/system/permission"), Map.of("permissionCode", code, "permissionName", "Boundary"));
        assertEquals(code, permissions.getById(1001L).getData().getPermissionCode());
        String updated = "admin:system:resource:view:" + "b".repeat(101);
        accept(
                put("/api/v1/admin/system/permission/1001"),
                Map.of("permissionCode", updated, "permissionName", "Boundary"));
        assertEquals(updated, permissions.getById(1001L).getData().getPermissionCode());
        reject(
                post("/api/v1/admin/system/permission"),
                Map.of("permissionCode", code + "c", "permissionName", "Overflow"));
        reject(
                put("/api/v1/admin/system/permission/1001"),
                Map.of("permissionCode", updated + "c", "permissionName", "Overflow"));
        assertEquals(updated, permissions.getById(1001L).getData().getPermissionCode());
        assertEquals(1, permissions.page(1, 10).getData().getRecords().size());
    }

    private void reject(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        try {
            var result = mvc.perform(
                            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                    .andReturn();
            assertInstanceOf(MethodArgumentNotValidException.class, result.getResolvedException());
        } catch (ServletException exception) {
            var failure = assertInstanceOf(BusinessException.class, exception.getCause());
            assertEquals(320, failure.getCode());
        }
    }

    private void accept(MockHttpServletRequestBuilder request, Map<String, ?> body) throws Exception {
        var result = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andReturn();
        assertNull(result.getResolvedException());
        assertEquals(200, result.getResponse().getStatus());
    }

    private void rejectBusiness(MockHttpServletRequestBuilder request, Map<String, ?> body, String errorCode) {
        var exception = assertThrows(
                ServletException.class,
                () -> mvc.perform(
                        request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))));
        assertEquals(
                errorCode,
                assertInstanceOf(BusinessException.class, exception.getCause()).getErrorCode());
    }
}
