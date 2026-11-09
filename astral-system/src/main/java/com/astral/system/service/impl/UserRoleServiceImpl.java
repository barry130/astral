package com.astral.system.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.astral.auth.security.PermissionCache;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.notify.UserRoleChangeNotifier;
import com.astral.system.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 用户角色关联服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供用户角色关联实体的标准CRUD操作</p>
 *
 * <p>这里直接注入 {@link RoleMapper} 而不是 {@code RoleService}：{@code RoleServiceImpl}
 * 需要反向统计用户角色引用数，互相注入服务会形成 Bean 循环依赖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {

    private static final JsonMapper SNAPSHOT_JSON = JsonMapper.builder().build();

    private final RoleMapper roleMapper;
    private final UserRoleChangeNotifier roleChangeNotifier;
    private final PermissionCache permissionCache;
    private final UserMapper userMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        replaceRoles(userId, roleIds, null, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String assignmentSnapshot(Long userId, List<Long> roleIds) {
        List<User> users = lockUsers(List.of(userId));
        if (users.stream().noneMatch(user -> Objects.equals(user.getId(), userId))) return null;
        return snapshot(userId, roleIds, users);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRolesIfUnchanged(Long userId, List<Long> roleIds, String expected) {
        replaceRoles(userId, roleIds, expected, true);
    }

    private void replaceRoles(Long userId, List<Long> roleIds, String expected, boolean checkSnapshot) {
        List<User> lockedUsers = lockUsers(List.of(userId));
        User user = lockedUsers.stream()
                .filter(row -> Objects.equals(row.getId(), userId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("SYS001"));
        if (checkSnapshot && (expected == null || !expected.equals(snapshot(userId, roleIds, lockedUsers)))) {
            throw new BusinessException("SYS017");
        }
        // Role definitions are locked in the same order as permission assignment writers.
        lockRoles();
        List<Long> distinct = (roleIds == null || roleIds.isEmpty())
                ? List.of()
                : roleIds.stream().filter(Objects::nonNull).distinct().toList();

        // 1) 先校验角色ID都真实存在：脏ID直接拒绝。
        //    原实现静默入库，后续权限查询查不到角色，表现为「分配成功但没有权限」，很难排查。
        if (!distinct.isEmpty()) {
            Long found = roleMapper.selectCount(new LambdaQueryWrapper<Role>().in(Role::getId, distinct));
            long foundCount = found == null ? 0L : found;
            if (foundCount != distinct.size()) {
                log.warn("分配角色失败：存在不存在的角色ID, userId={}, 期望={}, 实际={}", userId, distinct.size(), foundCount);
                throw new BusinessException("SYS012", distinct.size() - foundCount);
            }
        }

        List<Long> before = list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId)).stream()
                .map(UserRole::getRoleId)
                .toList();

        protectLastSuper(user, before, distinct, lockedUsers);

        // 2) 清空 + 写入必须同一事务：中途失败会让用户角色被清空且无法自愈
        remove(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId));

        if (!distinct.isEmpty()) {
            List<UserRole> rows = new ArrayList<>(distinct.size());
            for (Long roleId : distinct) {
                UserRole ur = new UserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                rows.add(ur);
            }
            saveBatch(rows);
        }
        permissionCache.bumpAfterCommit();
        roleChangeNotifier.notifyChange(userId, before, distinct);
    }

    private void protectLastSuper(User user, List<Long> before, List<Long> distinct, List<User> lockedUsers) {
        Long userId = user.getId();
        List<Long> superRoles =
                roleMapper
                        .selectList(new LambdaQueryWrapper<Role>()
                                .eq(Role::getStatus, 1)
                                .eq(Role::getIsSuper, 1))
                        .stream()
                        .map(Role::getId)
                        .toList();
        if ("ADMIN".equals(user.getUserType())
                && Integer.valueOf(1).equals(user.getStatus())
                && before.stream().anyMatch(superRoles::contains)
                && distinct.stream().noneMatch(superRoles::contains)) {
            List<Long> otherAdmins = lockedUsers.stream()
                    .filter(row -> !Objects.equals(row.getId(), userId)
                            && "ADMIN".equals(row.getUserType())
                            && Integer.valueOf(1).equals(row.getStatus()))
                    .map(User::getId)
                    .toList();
            if (otherAdmins.isEmpty()
                    || count(new LambdaQueryWrapper<UserRole>()
                                    .in(UserRole::getUserId, otherAdmins)
                                    .in(UserRole::getRoleId, superRoles))
                            == 0) {
                throw new BusinessException("SYS016");
            }
        }
    }

    private void protectRemoval(UserRole before, Long newUserId, Long newRoleId, List<User> lockedUsers) {
        if (before == null) return;
        User user = lockedUsers.stream()
                .filter(row -> Objects.equals(row.getId(), before.getUserId()))
                .findFirst()
                .orElse(null);
        if (user == null) return;
        List<UserRole> current = list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, before.getUserId()));
        List<Long> after = new ArrayList<>(current.stream()
                .filter(row -> !Objects.equals(row.getId(), before.getId()))
                .map(UserRole::getRoleId)
                .toList());
        if (Objects.equals(before.getUserId(), newUserId)) after.add(newRoleId);
        protectLastSuper(user, current.stream().map(UserRole::getRoleId).toList(), after, lockedUsers);
    }

    private List<User> lockUsers(List<Long> ids) {
        // Share the ordered administrator lock with status changes. Role replacement and
        // direct link writes must use users -> roles -> permissions, never the reverse.
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                .and(q -> q.eq(User::getUserType, "ADMIN").or().in(User::getId, ids))
                .eq(User::getDeleted, 0)
                .orderByAsc(User::getId)
                .last("FOR UPDATE"));
    }

    private List<Role> lockRoles() {
        // Lock the same parent set as role/permission relation writers and role deletion.
        return roleMapper.selectList(
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getId).last("FOR UPDATE"));
    }

    private String snapshot(Long userId, List<Long> requested, List<User> users) {
        User user = users.stream()
                .filter(row -> Objects.equals(row.getId(), userId))
                .findFirst()
                .orElseThrow(() -> new BusinessException("SYS001"));
        List<Long> current = list(new LambdaQueryWrapper<UserRole>().eq(UserRole::getUserId, userId)).stream()
                .map(UserRole::getRoleId)
                .distinct()
                .sorted()
                .toList();
        var relevant = new TreeSet<>(current);
        if (requested != null) requested.stream().filter(Objects::nonNull).forEach(relevant::add);
        List<Role> definitions = lockRoles().stream()
                .filter(role -> relevant.contains(role.getId()))
                .toList();
        List<RolePermission> links = relevant.isEmpty()
                ? List.of()
                : rolePermissionMapper.selectList(new LambdaQueryWrapper<RolePermission>()
                        .in(RolePermission::getRoleId, relevant)
                        .orderByAsc(RolePermission::getRoleId, RolePermission::getPermissionId));
        List<Long> permissionIds = links.stream()
                .map(RolePermission::getPermissionId)
                .distinct()
                .sorted()
                .toList();
        List<Permission> permissions = permissionIds.isEmpty()
                ? List.of()
                : permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                        .in(Permission::getId, permissionIds)
                        .orderByAsc(Permission::getId)
                        .last("FOR UPDATE"));
        // Stable values, not cache versions or model-provided data; detect changes to the
        // current assignment and the meaning of both existing and requested roles.
        var value = List.of(
                Arrays.asList(user.getId(), user.getUserType(), user.getStatus()),
                current,
                definitions.stream()
                        .map(role ->
                                Arrays.asList(role.getId(), role.getRoleCode(), role.getStatus(), role.getIsSuper()))
                        .toList(),
                links.stream()
                        .map(link -> List.of(link.getRoleId(), link.getPermissionId()))
                        .toList(),
                permissions.stream()
                        .map(permission -> Arrays.asList(
                                permission.getId(), permission.getPermissionCode(), permission.getStatus()))
                        .toList());
        return DigestUtil.sha256Hex(SNAPSHOT_JSON.writeValueAsString(value));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(UserRole entity) {
        requireUser(entity.getUserId(), lockUsers(List.of(entity.getUserId())));
        lockRoles();
        requireRole(entity.getRoleId());
        boolean saved = super.save(entity);
        if (saved) {
            permissionCache.bumpAfterCommit();
            roleChangeNotifier.notifyChange(entity.getUserId(), List.of(), List.of(entity.getRoleId()));
        }
        return saved;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(UserRole entity) {
        return updateAssignment(entity.getId(), entity.getUserId(), entity.getRoleId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateAssignment(Long id, Long userId, Long roleId) {
        UserRole observed = getById(id);
        if (observed == null) return false;
        List<User> lockedUsers =
                lockUsers(userId == null ? List.of(observed.getUserId()) : List.of(observed.getUserId(), userId));
        lockRoles();
        UserRole before = lockedAssignment(id);
        if (before == null || (userId == null && roleId == null)) {
            return false;
        }
        if (userId != null) requireUser(userId, lockedUsers);
        if (roleId != null) requireRole(roleId);
        protectRemoval(
                before,
                userId == null ? before.getUserId() : userId,
                roleId == null ? before.getRoleId() : roleId,
                lockedUsers);
        boolean updated = lambdaUpdate()
                .eq(UserRole::getId, id)
                .set(userId != null, UserRole::getUserId, userId)
                .set(roleId != null, UserRole::getRoleId, roleId)
                .update();
        if (updated) permissionCache.bumpAfterCommit();
        notifyUpdated(before, id, updated);
        return updated;
    }

    private UserRole lockedAssignment(Serializable id) {
        return baseMapper.selectOne(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getId, id).last("FOR UPDATE"));
    }

    private void notifyUpdated(UserRole before, Long id, boolean updated) {
        if (updated && before != null) {
            // 重新读取以兼容局部更新：未传字段由 MyBatis-Plus 保留原值。
            UserRole after = getById(id);
            if (after == null) {
                return;
            }
            if (Objects.equals(before.getUserId(), after.getUserId())) {
                roleChangeNotifier.notifyChange(
                        after.getUserId(), List.of(before.getRoleId()), List.of(after.getRoleId()));
            } else {
                roleChangeNotifier.notifyChange(before.getUserId(), List.of(before.getRoleId()), List.of());
                roleChangeNotifier.notifyChange(after.getUserId(), List.of(), List.of(after.getRoleId()));
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        UserRole observed = getById(id);
        if (observed == null) return false;
        List<User> lockedUsers = lockUsers(List.of(observed.getUserId()));
        lockRoles();
        UserRole before = lockedAssignment(id);
        protectRemoval(before, null, null, lockedUsers);
        boolean removed = super.removeById(id);
        if (removed && before != null) {
            permissionCache.bumpAfterCommit();
            roleChangeNotifier.notifyChange(before.getUserId(), List.of(before.getRoleId()), List.of());
        }
        return removed;
    }

    private void requireRole(Long roleId) {
        if (roleId == null || roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getId, roleId)) == 0) {
            throw new BusinessException("SYS003");
        }
    }

    private void requireUser(Long userId, List<User> lockedUsers) {
        if (lockedUsers.stream().noneMatch(user -> Objects.equals(user.getId(), userId))) {
            throw new BusinessException("SYS001");
        }
    }
}
