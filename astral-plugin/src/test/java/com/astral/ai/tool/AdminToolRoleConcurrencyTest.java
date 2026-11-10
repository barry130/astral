package com.astral.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.common.AiJson;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.auth.registry.AppUserRoleService;
import com.astral.auth.security.PermissionCache;
import com.astral.common.exception.BusinessException;
import com.astral.common.util.SqlIdentifiers;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.controller.PermissionController;
import com.astral.system.controller.RolePermissionController;
import com.astral.system.notify.NotifyPublisher;
import com.astral.system.notify.SysNotifyRuleServiceImpl;
import com.astral.system.notify.UserRoleChangeNotifier;
import com.astral.system.service.impl.PermissionServiceImpl;
import com.astral.system.service.impl.RolePermissionServiceImpl;
import com.astral.system.service.impl.RoleServiceImpl;
import com.astral.system.service.impl.UserRoleServiceImpl;
import com.astral.system.service.impl.UserServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.reflection.MetaObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Public actions, real PostgreSQL transactions; JDBC only schedules two independent callers. */
@EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+")
class AdminToolRoleConcurrencyTest {
    private JdbcTemplate admin;
    private String schema;
    private String secondApplication;
    private UserServiceImpl users;
    private AdminToolProvider provider;
    private UserRoleServiceImpl roles;
    private RolePermissionServiceImpl rolePermissions;
    private RoleServiceImpl roleService;
    private AppUserRoleService appRoles;
    private PermissionController permissions;
    private RolePermissionController permissionLinks;
    private final AtomicBoolean pausePermissionLookup = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> permissionWriter = ThreadLocal.withInitial(() -> false);
    private String firstSnapshot;
    private String secondSnapshot;
    private final CountDownLatch firstCount = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final AtomicBoolean pauseCount = new AtomicBoolean(false);
    private final CountDownLatch defaultRoleLookup = new CountDownLatch(1);
    private final CountDownLatch releaseDefaultRoleLookup = new CountDownLatch(1);
    private final AtomicBoolean pauseDefaultRoleLookup = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> secondCaller = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> appCaller = ThreadLocal.withInitial(() -> false);
    private final ThreadLocal<Boolean> deleteCaller = ThreadLocal.withInitial(() -> false);

    @BeforeEach
    void setUp() throws Exception {
        String url = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        admin = new JdbcTemplate(new DriverManagerDataSource(url, "astral_audit", ""));
        schema = "role_assignment_" + UUID.randomUUID().toString().replace("-", "");
        secondApplication = schema + "_second";
        admin.execute(String.format("CREATE SCHEMA %s", SqlIdentifiers.requireSafe(schema)));
        var database = new DriverManagerDataSource(url + "?currentSchema=" + schema, "astral_audit", "");
        var source = new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return schedule(database.getConnection());
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return schedule(database.getConnection(username, password));
            }
        };
        var jdbc = new JdbcTemplate(source);
        for (String table : List.of(
                "sys_user", "sys_role", "sys_user_role", "sys_permission", "sys_role_permission", "sys_notify_rule")) {
            var metadata = AiJson.read(
                    Files.readString(Path.of("../astral-schema/src/main/resources/schema/" + table + ".json")));
            var columns = new ArrayList<String>();
            for (var field : metadata.path("fields"))
                columns.add(field.path("columnName").asString() + " "
                        + field.path("jdbcType").asString().replace("TINYINT", "SMALLINT")
                        + (field.path("isPrimaryKey").asBoolean(false) ? " PRIMARY KEY" : ""));
            jdbc.execute(String.format(
                    "CREATE TABLE %s (%s)", SqlIdentifiers.requireSafe(table), String.join(",", columns)));
        }
        jdbc.update(
                "INSERT INTO sys_user(id,username,status,user_type,deleted) VALUES(1,'first',1,'ADMIN',0),(2,'second',1,'ADMIN',0)");
        jdbc.update(
                "INSERT INTO sys_role(id,role_code,status,is_super) VALUES(1,'super',1,1),(2,'ordinary',1,0),(3,'other',1,0)");
        jdbc.update("INSERT INTO sys_user_role(id,user_id,role_id) VALUES(1,1,1),(2,2,1)");
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
        configuration.addMapper(UserMapper.class);
        configuration.addMapper(RoleMapper.class);
        configuration.addMapper(UserRoleMapper.class);
        configuration.addMapper(PermissionMapper.class);
        configuration.addMapper(RolePermissionMapper.class);
        configuration.addMapper(SysNotifyRuleMapper.class);
        var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(configuration));
        var service = new UserServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", session.getMapper(UserMapper.class));
        var transaction = new ProxyFactory(service);
        transaction.setProxyTargetClass(true);
        transaction.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        users = (UserServiceImpl) transaction.getProxy();
        var ruleService = new SysNotifyRuleServiceImpl(null, null, null, null);
        ReflectionTestUtils.setField(ruleService, "baseMapper", session.getMapper(SysNotifyRuleMapper.class));
        var publisher =
                new NotifyPublisher(ruleService, null, null, null, null, null, null, null, null, null, null, null);
        var roleMapper = session.getMapper(RoleMapper.class);
        var roleTarget = new UserRoleServiceImpl(
                roleMapper,
                new UserRoleChangeNotifier(roleMapper, publisher),
                new PermissionCache(),
                session.getMapper(UserMapper.class),
                session.getMapper(RolePermissionMapper.class),
                session.getMapper(PermissionMapper.class));
        ReflectionTestUtils.setField(roleTarget, "baseMapper", session.getMapper(UserRoleMapper.class));
        var roleProxy = new ProxyFactory(roleTarget);
        roleProxy.setProxyTargetClass(true);
        roleProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        roles = (UserRoleServiceImpl) roleProxy.getProxy();
        var permissionTarget = new PermissionServiceImpl(session.getMapper(RolePermissionMapper.class));
        ReflectionTestUtils.setField(permissionTarget, "baseMapper", session.getMapper(PermissionMapper.class));
        var permissionProxy = new ProxyFactory(permissionTarget);
        permissionProxy.setProxyTargetClass(true);
        permissionProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        var permissionService = (PermissionServiceImpl) permissionProxy.getProxy();
        permissions = new PermissionController(permissionService, new PermissionCache());
        var relationTarget = new RolePermissionServiceImpl(permissionService, roleMapper);
        ReflectionTestUtils.setField(relationTarget, "baseMapper", session.getMapper(RolePermissionMapper.class));
        var relationProxy = new ProxyFactory(relationTarget);
        relationProxy.setProxyTargetClass(true);
        relationProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        rolePermissions = (RolePermissionServiceImpl) relationProxy.getProxy();
        permissionLinks = new RolePermissionController(rolePermissions, new PermissionCache());
        jdbc.update("INSERT INTO sys_permission(id,permission_code,status) VALUES(10,'admin:fixture:read',1)");
        jdbc.update("INSERT INTO sys_permission(id,permission_code,status,type) VALUES(11,'user:fixture:read',1,4)");
        jdbc.update("UPDATE sys_role SET role_code='APP_USER' WHERE id=3");
        var roleServiceTarget = new RoleServiceImpl(session.getMapper(UserRoleMapper.class), rolePermissions);
        ReflectionTestUtils.setField(roleServiceTarget, "baseMapper", roleMapper);
        var roleServiceProxy = new ProxyFactory(roleServiceTarget);
        roleServiceProxy.setProxyTargetClass(true);
        roleServiceProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        roleService = (RoleServiceImpl) roleServiceProxy.getProxy();
        appRoles = new AppUserRoleService(
                roleMapper,
                session.getMapper(PermissionMapper.class),
                session.getMapper(RolePermissionMapper.class),
                session.getMapper(UserRoleMapper.class),
                new PermissionCache(),
                session.getMapper(UserMapper.class));
        var appProxy = new ProxyFactory(appRoles);
        appProxy.setProxyTargetClass(true);
        appProxy.addAdvice(new TransactionInterceptor(
                new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        appRoles = (AppUserRoleService) appProxy.getProxy();
        provider = new AdminToolProvider(
                users, null, roles, null, null, null, null, null, null, null, null, null, null, null, jdbc, null);
        firstSnapshot = roles.assignmentSnapshot(1L, List.of(2L));
        secondSnapshot = roles.assignmentSnapshot(2L, List.of(2L));
        pauseCount.set(true);
    }

    @AfterEach
    void clean() {
        releaseFirst.countDown();
        releaseDefaultRoleLookup.countDown();
        if (schema != null) admin.execute(String.format("DROP SCHEMA %s CASCADE", SqlIdentifiers.requireSafe(schema)));
    }

    @Test
    void concurrentApprovedRoleChangesKeepOneAvailableSuperAdministrator() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> demote(1L, firstSnapshot));
            assertTrue(firstCount.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondCaller.set(true);
                try {
                    return demote(2L, secondSnapshot);
                } finally {
                    secondCaller.remove();
                }
            });
            releaseWhenBlockedOrCompleted(second);
            assertEquals(AiToolResult.SUCCEEDED, first.get(10, TimeUnit.SECONDS));
            assertEquals(AiToolResult.REJECTED, second.get(10, TimeUnit.SECONDS));
        }
        assertEquals(List.of(2L), assigned(1L));
        assertEquals(List.of(1L), assigned(2L));
    }

    @Test
    void concurrentWholeReplacementDoesNotUnionTwoRequestedSets() throws Exception {
        pauseCount.set(false);
        roles.assignRoles(1L, List.of());
        pauseCount.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> roles.assignRoles(1L, List.of(2L)));
            assertTrue(firstCount.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondCaller.set(true);
                try {
                    roles.assignRoles(1L, List.of(3L));
                } finally {
                    secondCaller.remove();
                }
            });
            releaseWhenBlockedOrCompleted(second);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of(3L), assigned(1L));
    }

    @Test
    void approvedReplacementHoldsAuthorizationStableUntilItsTransactionCommits() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> demote(1L, firstSnapshot));
            assertTrue(firstCount.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondCaller.set(true);
                try {
                    rolePermissions.assignPermissions(2L, List.of(10L));
                } finally {
                    secondCaller.remove();
                }
            });
            assertTrue(
                    releaseWhenBlockedOrCompleted(second),
                    "a public permission write must wait for the approved role replacement to commit");
            assertEquals(AiToolResult.SUCCEEDED, first.get(10, TimeUnit.SECONDS));
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of(2L), assigned(1L));
        assertEquals(
                List.of(10L),
                rolePermissions.list().stream()
                        .map(RolePermission::getPermissionId)
                        .toList());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"assign", "sync"})
    void appRoleWritersRejectRoleDeletedAfterLookup(String operation) throws Exception {
        pauseCount.set(false);
        pauseDefaultRoleLookup.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var app = executor.submit(() -> {
                appCaller.set(true);
                try {
                    if ("assign".equals(operation)) {
                        appRoles.assignToUser(1L);
                    } else {
                        appRoles.syncPermissions();
                    }
                } finally {
                    appCaller.remove();
                }
            });
            assertTrue(defaultRoleLookup.await(5, TimeUnit.SECONDS));
            var deletion = executor.submit(() -> {
                deleteCaller.set(true);
                try {
                    roleService.deleteRoleWithRelations(3L);
                } finally {
                    deleteCaller.remove();
                }
            });
            try {
                deletion.get(10, TimeUnit.SECONDS);
                assertNull(roleService.getById(3L));
            } finally {
                releaseDefaultRoleLookup.countDown();
            }
            ExecutionException failure = assertThrows(ExecutionException.class, () -> app.get(10, TimeUnit.SECONDS));
            assertEquals("SYS003", ((BusinessException) failure.getCause()).getErrorCode());
        }
        assertNull(roleService.getById(3L));
        assertTrue(roles.list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, 3L))
                .isEmpty());
        assertTrue(rolePermissions
                .list(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, 3L))
                .isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"batch", "sync", "create", "update"})
    void permissionDeletionCannotInvalidateAConcurrentGrant(String operation) throws Exception {
        pauseCount.set(false);
        var link = new RolePermission();
        link.setRoleId(3L);
        link.setPermissionId(10L);
        if ("update".equals(operation)) permissionLinks.create(link);
        pausePermissionLookup.set(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var grant = executor.submit(() -> {
                permissionWriter.set(true);
                try {
                    if ("batch".equals(operation)) rolePermissions.assignPermissions(3L, List.of(11L));
                    else if ("sync".equals(operation)) appRoles.syncPermissions();
                    else {
                        var request = new RolePermission();
                        request.setRoleId(3L);
                        request.setPermissionId(11L);
                        if ("create".equals(operation)) permissionLinks.create(request);
                        else permissionLinks.update(link.getId(), request);
                    }
                } finally {
                    permissionWriter.remove();
                }
            });
            try {
                assertTrue(firstCount.await(5, TimeUnit.SECONDS));
                var deletion = executor.submit(() -> {
                    secondCaller.set(true);
                    try {
                        return permissions.delete(11L);
                    } finally {
                        secondCaller.remove();
                    }
                });
                releaseWhenBlockedOrCompleted(deletion);
                grant.get(10, TimeUnit.SECONDS);
                var failure = assertThrows(ExecutionException.class, () -> deletion.get(10, TimeUnit.SECONDS));
                assertEquals("SYS006", ((BusinessException) failure.getCause()).getErrorCode());
            } finally {
                releaseFirst.countDown();
            }
        }
        assertEquals(11L, permissions.getById(11L).getData().getId());
        assertEquals(
                List.of(11L),
                rolePermissions.list().stream()
                        .map(RolePermission::getPermissionId)
                        .toList());
    }

    private List<Long> assigned(Long userId) {
        return roles.list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId)).stream()
                .map(UserRole::getRoleId)
                .sorted()
                .toList();
    }

    private boolean releaseWhenBlockedOrCompleted(java.util.concurrent.Future<?> second) throws Exception {
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean waiting = false;
            while (!second.isDone() && !waiting && System.nanoTime() < deadline) {
                waiting = Boolean.TRUE.equals(admin.queryForObject(
                        "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock')",
                        Boolean.class,
                        secondApplication));
                if (!waiting) Thread.sleep(10);
            }
            assertTrue(second.isDone() || waiting);
            return waiting;
        } finally {
            releaseFirst.countDown();
        }
    }

    private String demote(long userId, String snapshot) {
        {
            var args = AiJson.mapper().createObjectNode().put("userId", userId).put("_authorizationSnapshot", snapshot);
            args.putArray("roleIds").add(2);
            return provider.handlers().stream()
                    .filter(h -> "change_role_permission".equals(h.definition().toolCode()))
                    .findFirst()
                    .orElseThrow()
                    .execute(new AiToolContext(
                            10L, 11L, 12L, 13L, "fixture", "ADMIN", "ASTRAL_FRONT", args, null, true, 12L))
                    .status();
        }
    }

    private Connection schedule(Connection connection) throws SQLException {
        if (secondCaller.get()) connection.setClientInfo("ApplicationName", secondApplication);
        if (deleteCaller.get()) connection.setClientInfo("ApplicationName", schema + "_delete");
        if (appCaller.get()) connection.setClientInfo("ApplicationName", schema + "_app");
        return (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(connection, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                    if (!(result instanceof Statement statement)) return result;
                    String preparedSql = args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
                    Class<?> statementType =
                            statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                    return Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {statementType},
                            (statementProxy, statementMethod, statementArgs) -> {
                                Object value;
                                try {
                                    value = statementMethod.invoke(statement, statementArgs);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                                String sql = preparedSql != null
                                        ? preparedSql
                                        : statementArgs != null
                                                        && statementArgs.length > 0
                                                        && statementArgs[0] instanceof String text
                                                ? text
                                                : "";
                                String normalized = sql.toLowerCase(Locale.ROOT);
                                if (permissionWriter.get()
                                        && (statementMethod.getName().equals("execute")
                                                || statementMethod.getName().equals("executeQuery"))
                                        && normalized.startsWith("select")
                                        && normalized.contains("from sys_permission")
                                        && pausePermissionLookup.compareAndSet(true, false)) {
                                    firstCount.countDown();
                                    if (!releaseFirst.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("permission lookup was not released");
                                }
                                if (appCaller.get()
                                        && (statementMethod.getName().equals("execute")
                                                || statementMethod.getName().equals("executeQuery"))
                                        && normalized.startsWith("select")
                                        && normalized.contains("from sys_role")
                                        && normalized.contains("role_code")
                                        && !normalized.contains("for update")
                                        && pauseDefaultRoleLookup.compareAndSet(true, false)) {
                                    defaultRoleLookup.countDown();
                                    if (!releaseDefaultRoleLookup.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("default role lookup was not released");
                                }
                                if ((statementMethod.getName().equals("executeUpdate")
                                                || statementMethod.getName().equals("execute"))
                                        && normalized.startsWith("delete")
                                        && normalized.contains("sys_user_role")
                                        && pauseCount.compareAndSet(true, false)) {
                                    firstCount.countDown();
                                    if (!releaseFirst.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("first action was not released");
                                }
                                return value;
                            });
                });
    }
}
