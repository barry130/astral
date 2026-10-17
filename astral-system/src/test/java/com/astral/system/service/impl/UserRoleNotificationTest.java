package com.astral.system.service.impl;

import com.astral.dao.entity.Role;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.notify.NotifyPublisher;
import com.astral.system.notify.UserRoleChangeNotifier;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Spring transactions and H2 writes verify that rolled-back role changes never produce notifications. */
class UserRoleNotificationTest {
    private UserRoleServiceImpl service;
    private NotifyPublisher publisher;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private boolean failBatch;
    private final AtomicLong ids = new AtomicLong(10);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "roles-test"), UserRole.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "roles-test"), Role.class);
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
        source.setUrl(source.getUrl() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE user_roles (id BIGINT PRIMARY KEY, user_id BIGINT, role_id BIGINT)");
        RoleMapper roles = mock(RoleMapper.class);
        when(roles.selectCount(any())).thenReturn(1L);
        Role tester = new Role();
        tester.setId(2L);
        tester.setRoleName("轻听测试者");
        Role member = new Role();
        member.setId(1L);
        member.setRoleName("App 用户");
        when(roles.selectList(any(Wrapper.class))).thenReturn(List.of(member, tester));
        publisher = mock(NotifyPublisher.class);
        UserRoleMapper mapper = mock(UserRoleMapper.class);
        when(mapper.insert(any(UserRole.class))).thenAnswer(call -> insert(call.getArgument(0)));
        when(mapper.updateById(any(UserRole.class))).thenAnswer(call -> {
            UserRole row = call.getArgument(0);
            UserRole before = read(row.getId());
            return before == null ? 0 : jdbc.update("UPDATE user_roles SET user_id=?, role_id=? WHERE id=?",
                    row.getUserId() == null ? before.getUserId() : row.getUserId(),
                    row.getRoleId() == null ? before.getRoleId() : row.getRoleId(), row.getId());
        });
        when(mapper.deleteById(any(java.io.Serializable.class))).thenAnswer(call ->
                jdbc.update("DELETE FROM user_roles WHERE id=?", (Object) call.getArgument(0)));
        UserRoleServiceImpl target = spy(new UserRoleServiceImpl(roles, new UserRoleChangeNotifier(roles, publisher)));
        ReflectionTestUtils.setField(target, "baseMapper", mapper);
        doAnswer(call -> jdbc.query("SELECT * FROM user_roles WHERE user_id=7", (rs, n) -> {
            UserRole row = new UserRole();
            row.setId(rs.getLong("id"));
            row.setUserId(rs.getLong("user_id"));
            row.setRoleId(rs.getLong("role_id"));
            return row;
        })).when(target).list(any(Wrapper.class));
        doAnswer(call -> jdbc.update("DELETE FROM user_roles WHERE user_id=7") > 0)
                .when(target).remove(any(Wrapper.class));
        doAnswer(call -> {
            Collection<UserRole> rows = call.getArgument(0);
            for (UserRole row : rows) insert(row);
            if (failBatch) throw new DataIntegrityViolationException("simulated role write failure");
            return true;
        }).when(target).saveBatch(anyCollection());
        doAnswer(call -> read(call.getArgument(0))).when(target).getById(any());
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        transaction = new TransactionTemplate(manager);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(manager);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        advice.afterPropertiesSet();
        factory.addAdvice(advice);
        service = (UserRoleServiceImpl) factory.getProxy();
    }

    @Test
    void assignmentNotifiesOnlyAfterCommitWithAddedAndRemovedNames() {
        jdbc.update("INSERT INTO user_roles VALUES (1,7,1)");
        transaction.executeWithoutResult(status -> {
            service.assignRoles(7L, List.of(2L));
            verifyNoInteractions(publisher);
        });
        Map<String, String> payload = onlyPayload();
        assertEquals("7", payload.get("userId"));
        assertEquals("「轻听测试者」", payload.get("addedRoles"));
        assertEquals("「App 用户」", payload.get("removedRoles"));
        assertEquals("您已获得「轻听测试者」角色权限。您已被移除「App 用户」角色权限。", payload.get("changeSummary"));
    }

    @Test
    void unchangedOrDuplicateRolesDoNotNotify() {
        jdbc.update("INSERT INTO user_roles VALUES (1,7,2)");
        service.assignRoles(7L, List.of(2L, 2L));
        verifyNoInteractions(publisher);
    }

    @Test
    void clearingRolesNotifiesRemoval() {
        jdbc.update("INSERT INTO user_roles VALUES (1,7,2)");
        service.assignRoles(7L, null);
        assertEquals("您已被移除「轻听测试者」角色权限。", onlyPayload().get("changeSummary"));
    }

    @Test
    void outerRollbackDiscardsNotificationAndRoleWrites() {
        jdbc.update("INSERT INTO user_roles VALUES (1,7,1)");
        transaction.executeWithoutResult(status -> {
            service.assignRoles(7L, List.of(2L));
            status.setRollbackOnly();
        });
        verifyNoInteractions(publisher);
        assertEquals(1L, jdbc.queryForObject("SELECT role_id FROM user_roles", Long.class));
    }

    @Test
    void failedRoleInsertRollsBackWithoutNotification() {
        jdbc.update("INSERT INTO user_roles VALUES (1,7,1)");
        failBatch = true;
        assertThrows(DataIntegrityViolationException.class, () -> service.assignRoles(7L, List.of(2L)));
        verifyNoInteractions(publisher);
        assertEquals(1L, jdbc.queryForObject("SELECT role_id FROM user_roles", Long.class));
    }

    @Test
    void asyncDispatchFailureDoesNotFailCommittedRoleAssignment() {
        doThrow(new IllegalStateException("executor unavailable")).when(publisher).publishAsync(anyString(), anyMap());
        assertDoesNotThrow(() -> service.assignRoles(7L, List.of(2L)));
        assertEquals(2L, jdbc.queryForObject("SELECT role_id FROM user_roles", Long.class));
    }

    @Test
    void directCreateNotifiesGrant() {
        UserRole row = new UserRole();
        row.setUserId(7L);
        row.setRoleId(2L);
        assertTrue(service.save(row));
        assertEquals("「轻听测试者」", onlyPayload().get("addedRoles"));
    }

    @Test
    void directUpdateNotifiesActualDifferenceAndIgnoresUnchangedSave() {
        jdbc.update("INSERT INTO user_roles VALUES (1,7,1)");
        UserRole row = new UserRole();
        row.setId(1L);
        row.setRoleId(2L);
        assertTrue(service.updateById(row));
        assertEquals("「轻听测试者」", onlyPayload().get("addedRoles"));
        clearInvocations(publisher);
        assertTrue(service.updateById(row));
        verifyNoInteractions(publisher);
    }

    @Test
    void directDeleteNotifiesRemovalAndMissingDeleteDoesNotNotify() {
        jdbc.update("INSERT INTO user_roles VALUES (1,7,2)");
        assertTrue(service.removeById(1L));
        assertEquals("「轻听测试者」", onlyPayload().get("removedRoles"));
        clearInvocations(publisher);
        assertFalse(service.removeById(1L));
        verifyNoInteractions(publisher);
    }

    private int insert(UserRole row) {
        if (row.getId() == null) row.setId(ids.incrementAndGet());
        return jdbc.update("INSERT INTO user_roles VALUES (?,?,?)", row.getId(), row.getUserId(), row.getRoleId());
    }

    private UserRole read(Object id) {
        List<UserRole> rows = jdbc.query("SELECT * FROM user_roles WHERE id=?", (rs, n) -> {
            UserRole row = new UserRole();
            row.setId(rs.getLong("id"));
            row.setUserId(rs.getLong("user_id"));
            row.setRoleId(rs.getLong("role_id"));
            return row;
        }, id);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> onlyPayload() {
        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        verify(publisher).publishAsync(eq(NotifyEventRegistry.USER_ROLES_CHANGED), captor.capture());
        return captor.getValue();
    }
}
