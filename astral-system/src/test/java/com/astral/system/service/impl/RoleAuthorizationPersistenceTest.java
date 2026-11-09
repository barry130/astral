package com.astral.system.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.config.StpInterfaceImpl;
import com.astral.auth.security.PermissionCache;
import com.astral.common.exception.BusinessException;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.notify.NotifyPublisher;
import com.astral.system.notify.SysNotifyRuleServiceImpl;
import com.astral.system.notify.UserRoleChangeNotifier;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Public role writes and real cached authorization reads; only notification storage is isolated. */
class RoleAuthorizationPersistenceTest {
    private JdbcTemplate jdbc;
    private UserRoleServiceImpl roles;
    private StpInterfaceImpl permissions;
    private PermissionCache cache;
    private TransactionTemplate transaction;
    private StpLogic previousLogic;
    private com.astral.auth.registry.AppUserRoleService appRoles;

    @BeforeEach
    void setUp() throws Exception {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:roles_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("sys_user", "sys_role", "sys_permission", "sys_user_role", "sys_role_permission")) {
            var metadata = JsonMapper.builder()
                    .build()
                    .readTree(
                            Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + table + ".json")));
            var columns = new ArrayList<String>();
            for (var field : metadata.path("fields")) {
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            }
            jdbc.execute("CREATE TABLE " + table + " (" + String.join(",", columns) + ")");
        }
        jdbc.update("INSERT INTO sys_user(id,username,status,user_type,deleted) VALUES(7,'fixture',1,'ADMIN',0)");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,role_name,status,is_super) VALUES(1,'original','Original',1,0),(2,'replacement','Replacement',1,0)");
        jdbc.update(
                "INSERT INTO sys_permission(id,permission_code,status) VALUES(3,'admin:fixture:read',1),(4,'admin:fixture:edit',1)");
        jdbc.update("INSERT INTO sys_role_permission(id,role_id,permission_id) VALUES(5,1,3),(6,2,4)");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES(8,7,1)");
        var configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("fixture", new SpringManagedTransactionFactory(), source));
        configuration.setMapUnderscoreToCamelCase(true);
        var sequence = new AtomicLong(100);
        GlobalConfigUtils.setGlobalConfig(
                configuration,
                new GlobalConfig()
                        .setDbConfig(new GlobalConfig.DbConfig())
                        .setMetaObjectHandler(new MetaObjectHandler() {
                            @Override
                            public void insertFill(MetaObject row) {
                                if (getFieldValByName("id", row) == null)
                                    setFieldValByName("id", sequence.incrementAndGet(), row);
                            }

                            @Override
                            public void updateFill(MetaObject row) {}
                        }));
        configuration.addMapper(UserRoleMapper.class);
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(RoleMapper.class);
        configuration.addMapper(RolePermissionMapper.class);
        configuration.addMapper(PermissionMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var notifyRows = mock(SysNotifyRuleMapper.class);
        when(notifyRows.selectList(any())).thenReturn(List.of());
        var ruleService = new SysNotifyRuleServiceImpl();
        ReflectionTestUtils.setField(ruleService, "baseMapper", notifyRows);
        var publisher =
                new NotifyPublisher(ruleService, null, null, null, null, null, null, null, null, null, null, null);
        var roleMapper = session.getMapper(RoleMapper.class);
        cache = new PermissionCache();
        var target = new UserRoleServiceImpl(
                roleMapper,
                new UserRoleChangeNotifier(roleMapper, publisher),
                cache,
                session.getMapper(UserMapper.class),
                session.getMapper(RolePermissionMapper.class),
                session.getMapper(PermissionMapper.class));
        ReflectionTestUtils.setField(target, "baseMapper", session.getMapper(UserRoleMapper.class));
        var manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        roles = (UserRoleServiceImpl) proxy.getProxy();
        var appTarget = new com.astral.auth.registry.AppUserRoleService(
                roleMapper,
                session.getMapper(PermissionMapper.class),
                session.getMapper(RolePermissionMapper.class),
                session.getMapper(UserRoleMapper.class),
                cache,
                session.getMapper(UserMapper.class));
        var appProxy = new ProxyFactory(appTarget);
        appProxy.setProxyTargetClass(true);
        appProxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        appRoles = (com.astral.auth.registry.AppUserRoleService) appProxy.getProxy();
        permissions = new StpInterfaceImpl(
                session.getMapper(UserRoleMapper.class),
                roleMapper,
                session.getMapper(RolePermissionMapper.class),
                session.getMapper(PermissionMapper.class),
                cache);
        previousLogic = StpUtil.getStpLogic();
        var dao = new SaTokenDaoDefaultImpl();
        StpUtil.setStpLogic(new StpLogic("audit-roles") {
            @Override
            public SaTokenDao getSaTokenDao() {
                return dao;
            }
        });
    }

    @AfterEach
    void close() {
        if (previousLogic != null) StpUtil.setStpLogic(previousLogic);
        if (jdbc != null) jdbc.execute("SHUTDOWN");
    }

    @Test
    void committedRoleReplacementImmediatelyInvalidatesExistingAuthorization() {
        assertEquals(List.of("admin:fixture:read"), permissions.getPermissionList(7L, "login"));
        assertEquals(List.of("original"), permissions.getRoleList(7L, "login"));
        String before = cache.currentVersion();
        transaction.executeWithoutResult(status -> {
            roles.assignRoles(7L, List.of(2L));
            assertEquals(before, cache.currentVersion(), "uncommitted writes must not invalidate global authorization");
        });
        assertEquals(List.of("admin:fixture:edit"), permissions.getPermissionList(7L, "login"));
        assertEquals(List.of("replacement"), permissions.getRoleList(7L, "login"));
        assertTrue(roles.list().stream().allMatch(role -> role.getRoleId() == 2L));
    }

    @Test
    void rollbackKeepsTheExistingAuthorizationAndCacheVersion() {
        assertEquals(List.of("admin:fixture:read"), permissions.getPermissionList(7L, "login"));
        String before = cache.currentVersion();
        transaction.executeWithoutResult(status -> {
            roles.assignRoles(7L, List.of(2L));
            status.setRollbackOnly();
        });
        assertEquals(before, cache.currentVersion());
        assertEquals(List.of("admin:fixture:read"), permissions.getPermissionList(7L, "login"));
        assertEquals(
                List.of(1L), roles.list().stream().map(row -> row.getRoleId()).toList());
    }

    @Test
    void lastAvailableSuperAdministratorCannotLoseTheirSuperRole() {
        jdbc.update("UPDATE sys_role SET is_super=1 WHERE id=1");
        assertEquals(List.of("*:*:*"), permissions.getPermissionList(7L, "login"));
        assertThrows(BusinessException.class, () -> roles.assignRoles(7L, List.of(2L)));
        assertEquals(
                List.of(1L), roles.list().stream().map(row -> row.getRoleId()).toList());
        assertEquals(List.of("*:*:*"), permissions.getPermissionList(7L, "login"));
    }

    @Test
    void approvalSnapshotDetectsPermissionAndDirectAssignmentChanges() {
        String snapshot = roles.assignmentSnapshot(7L, List.of(2L));
        jdbc.update("UPDATE sys_permission SET permission_code='admin:fixture:delete' WHERE id=4");
        assertEquals(
                "SYS017",
                assertThrows(BusinessException.class, () -> roles.assignRolesIfUnchanged(7L, List.of(2L), snapshot))
                        .getErrorCode());
        String updated = roles.assignmentSnapshot(7L, List.of(2L));
        var added = new com.astral.dao.entity.UserRole();
        added.setUserId(7L);
        added.setRoleId(2L);
        roles.save(added);
        assertEquals(
                "SYS017",
                assertThrows(BusinessException.class, () -> roles.assignRolesIfUnchanged(7L, List.of(2L), updated))
                        .getErrorCode());
        assertEquals(
                List.of(1L, 2L),
                roles.list().stream().map(row -> row.getRoleId()).sorted().toList());
    }

    @Test
    void directRoleRemovalCannotBypassLastSuperAdministratorProtection() {
        jdbc.update("UPDATE sys_role SET is_super=1 WHERE id=1");
        assertEquals(
                "SYS016",
                assertThrows(BusinessException.class, () -> roles.removeById(8L))
                        .getErrorCode());
        assertEquals(
                List.of(1L), roles.list().stream().map(row -> row.getRoleId()).toList());
    }

    @Test
    void defaultAppRoleInvalidatesAuthorizationOnlyAfterCommit() {
        jdbc.update("UPDATE sys_role SET role_code='APP_USER' WHERE id=2");
        assertEquals(List.of("original"), permissions.getRoleList(7L, "login"));
        String before = cache.currentVersion();
        transaction.executeWithoutResult(status -> {
            appRoles.assignToUser(7L);
            assertEquals(before, cache.currentVersion());
        });
        assertEquals(
                List.of("APP_USER", "original"),
                permissions.getRoleList(7L, "login").stream().sorted().toList());
    }

    @Test
    void directRoleLinkCannotReferenceAMissingRole() {
        var invalid = new com.astral.dao.entity.UserRole();
        invalid.setUserId(7L);
        invalid.setRoleId(999L);
        assertThrows(BusinessException.class, () -> roles.save(invalid));
        assertEquals(
                List.of(1L), roles.list().stream().map(row -> row.getRoleId()).toList());
    }

    @Test
    void directRoleLinkCannotReferenceAMissingOrDeletedUser() {
        var invalid = new com.astral.dao.entity.UserRole();
        invalid.setUserId(999L);
        invalid.setRoleId(2L);
        assertEquals(
                "SYS001",
                assertThrows(BusinessException.class, () -> roles.save(invalid)).getErrorCode());
        assertEquals(
                "SYS001",
                assertThrows(BusinessException.class, () -> roles.updateAssignment(8L, 999L, 2L))
                        .getErrorCode());
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=7");
        invalid.setUserId(7L);
        assertEquals(
                "SYS001",
                assertThrows(BusinessException.class, () -> roles.save(invalid)).getErrorCode());
        assertEquals(7L, roles.getById(8L).getUserId());
        assertEquals(1L, roles.getById(8L).getRoleId());
    }
}
