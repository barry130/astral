package com.astral.system.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.auth.controller.ProfileController;
import com.astral.auth.security.RsaKeyManager;
import com.astral.auth.security.TotpUtil;
import com.astral.common.exception.BusinessException;
import com.astral.dao.mapper.UserMapper;
import com.astral.system.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/** Public credential endpoints with real MyBatis SQL; authentication and RSA are isolated adapters. */
class AccountCredentialPersistenceTest {
    private JdbcTemplate jdbc;
    private ProfileController profile;
    private UserController admin;
    private MockedStatic<StpUtil> login;
    private final BeforeWrite writes = new BeforeWrite();
    private final String oldPassword = "FixtureOld!7821";
    private final String nextPassword = "FixtureNew!9342";

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:credentials_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        var schema = JsonMapper.builder()
                .build()
                .readTree(Files.readString(Path.of("../astral-schema/src/main/resources/schema/sys_user.json")));
        var columns = new ArrayList<String>();
        for (var field : schema.path("fields"))
            columns.add(field.path("columnName").asString() + " "
                    + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT"));
        jdbc.execute("CREATE TABLE sys_user (" + String.join(",", columns) + ")");
        jdbc.update(
                "INSERT INTO sys_user(id,username,password,status,user_type,deleted,totp_enabled) VALUES(1,'fixture',?,1,'ADMIN',0,0)",
                BCrypt.hashpw(oldPassword));
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.addInterceptor(writes);
        config.addMapper(UserMapper.class);
        var mapper =
                new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config)).getMapper(UserMapper.class);
        var rsa = mock(RsaKeyManager.class);
        when(rsa.decryptPasswordBase64(anyString())).thenAnswer(call -> call.getArgument(0));
        profile = new ProfileController(mapper, rsa, null);
        var users = new UserServiceImpl();
        ReflectionTestUtils.setField(users, "baseMapper", mapper);
        admin = new UserController(users, null, null, null, null);
        login = mockStatic(StpUtil.class);
        login.when(StpUtil::getLoginIdAsLong).thenReturn(1L);
    }

    @AfterEach
    void close() {
        if (login != null) login.close();
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void passwordChangeAndAdminResetDoNotRestoreConcurrentAccountStatus() {
        writes.action.set(() -> jdbc.update("UPDATE sys_user SET status=0,user_type='APP' WHERE id=1"));
        var change = new ProfileController.ChangePasswordRequest();
        change.setOldPassword(oldPassword);
        change.setNewPassword(nextPassword);
        profile.changePassword(change);
        assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_user", Integer.class));
        assertEquals("APP", jdbc.queryForObject("SELECT user_type FROM sys_user", String.class));
        assertTrue(BCrypt.checkpw(nextPassword, jdbc.queryForObject("SELECT password FROM sys_user", String.class)));
        writes.action.set(() -> jdbc.update("UPDATE sys_user SET status=1,user_type='ADMIN' WHERE id=1"));
        admin.resetPassword(1L, Map.of("password", oldPassword));
        assertEquals(1, jdbc.queryForObject("SELECT status FROM sys_user", Integer.class));
        assertEquals("ADMIN", jdbc.queryForObject("SELECT user_type FROM sys_user", String.class));
        assertTrue(BCrypt.checkpw(oldPassword, jdbc.queryForObject("SELECT password FROM sys_user", String.class)));
    }

    @Test
    void stalePasswordAndTotpProofCannotOverwriteNewCredentials() {
        String changedHash = BCrypt.hashpw("AnotherPassword!218");
        writes.action.set(() -> jdbc.update("UPDATE sys_user SET password=? WHERE id=1", changedHash));
        var change = new ProfileController.ChangePasswordRequest();
        change.setOldPassword(oldPassword);
        change.setNewPassword(nextPassword);
        assertThrows(BusinessException.class, () -> profile.changePassword(change));
        assertEquals(changedHash, jdbc.queryForObject("SELECT password FROM sys_user", String.class));

        String firstSecret = profile.totpSetup().getData().get("secret");
        String secondSecret = TotpUtil.generateSecret();
        writes.action.set(() -> jdbc.update("UPDATE sys_user SET totp_secret=? WHERE id=1", secondSecret));
        var enable = new ProfileController.TotpEnableRequest();
        enable.setCode(TotpUtil.currentCode(firstSecret));
        assertThrows(BusinessException.class, () -> profile.totpEnable(enable));
        assertEquals(secondSecret, jdbc.queryForObject("SELECT totp_secret FROM sys_user", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT totp_enabled FROM sys_user", Integer.class));
    }

    @Intercepts(
            @Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class BeforeWrite implements Interceptor {
        final AtomicReference<Runnable> action = new AtomicReference<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            Runnable pending = action.getAndSet(null);
            if (pending != null) pending.run();
            return invocation.proceed();
        }
    }
}
