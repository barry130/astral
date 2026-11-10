package com.astral.qt.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.ai.common.AiJson;
import com.astral.dao.mapper.UserMapper;
import com.astral.qt.controller.QtAdminController;
import com.astral.qt.dto.QtChangePwByEmailDto;
import com.astral.qt.dto.QtUpdateUserDto;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import org.apache.ibatis.mapping.Environment;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class QtUserConcurrentUpdateTest {
    private JdbcTemplate jdbc;
    private QtUserService users;
    private QtAdminController admin;
    private Runnable afterRead;

    @BeforeEach
    void setUp() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:qt_user_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        var schema = AiJson.read(Files.readString(Path.of("../astral-schema/src/main/resources/schema/sys_user.json")));
        var columns = new ArrayList<String>();
        for (var field : schema.path("fields")) {
            columns.add(field.path("columnName").asString() + " "
                    + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                    + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
        }
        jdbc.execute(String.format("CREATE TABLE sys_user (%s)", String.join(",", columns)));
        jdbc.update(
                "INSERT INTO sys_user(id,username,email,password,status,user_type,deleted) VALUES(1,'fixture','fixture@example.test','old-password',1,'APP',0)");
        var config = new MybatisConfiguration();
        config.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(UserMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
        var boundary = new ProxyFactory(session.getMapper(UserMapper.class));
        boundary.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
            Object value = invocation.proceed();
            if (afterRead != null
                    && (invocation.getMethod().getName().equals("selectById")
                            || invocation.getMethod().getName().equals("selectOne"))) {
                Runnable action = afterRead;
                afterRead = null;
                action.run();
            }
            return value;
        });
        users = new QtUserService();
        admin = new QtAdminController();
        ReflectionTestUtils.setField(users, "userMapper", boundary.getProxy());
        ReflectionTestUtils.setField(admin, "userMapper", boundary.getProxy());
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(org.mockito.ArgumentMatchers.<RedisScript<Long>>any(), anyList(), any(Object[].class)))
                .thenReturn(1L);
        ReflectionTestUtils.setField(users, "stringRedisTemplate", redis);
    }

    @AfterEach
    void clean() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void profileEditPreservesAnInterveningBanAndPasswordReset() {
        afterRead = () -> jdbc.update("UPDATE sys_user SET status=0,password='new-password' WHERE id=1");
        var input = new QtUpdateUserDto();
        input.setEmail("fixture@example.test");
        input.setNickname("Renamed");
        input.setAvatar("fixture-avatar");
        try (var sessions = mockStatic(StpUtil.class)) {
            users.updateUser(1L, input);
        }
        assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_user WHERE id=1", Integer.class));
        assertEquals("new-password", jdbc.queryForObject("SELECT password FROM sys_user WHERE id=1", String.class));
        assertEquals("Renamed", jdbc.queryForObject("SELECT nickname FROM sys_user WHERE id=1", String.class));
    }

    @Test
    void banDoesNotRestoreThePasswordReadBeforeAReset() {
        afterRead = () -> jdbc.update("UPDATE sys_user SET password='new-password' WHERE id=1");
        try (var sessions = mockStatic(StpUtil.class)) {
            admin.changeUserState(1L, 0);
        }
        assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_user WHERE id=1", Integer.class));
        assertEquals("new-password", jdbc.queryForObject("SELECT password FROM sys_user WHERE id=1", String.class));
    }

    @Test
    void emailPasswordResetDoesNotUndoABan() {
        afterRead = () -> jdbc.update("UPDATE sys_user SET status=0 WHERE id=1");
        var input = new QtChangePwByEmailDto();
        input.setEmail("fixture@example.test");
        input.setPassword("fixture-new");
        input.setCode("123456");
        try (var sessions = mockStatic(StpUtil.class)) {
            users.changePwByEmail(input);
        }
        assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_user WHERE id=1", Integer.class));
        assertTrue(BCrypt.checkpw(
                "fixture-new", jdbc.queryForObject("SELECT password FROM sys_user WHERE id=1", String.class)));
    }
}
