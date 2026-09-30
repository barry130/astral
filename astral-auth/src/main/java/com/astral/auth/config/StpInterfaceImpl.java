package com.astral.auth.config;

import cn.dev33.satoken.stp.StpInterface;
import com.astral.auth.security.PermissionCache;
import com.astral.auth.security.PermissionChecker;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sa-Token 权限/角色数据源。
 *
 * <p>权限模型：用户(User) → 用户角色关联(UserRole) → 角色(Role) → 角色权限关联(RolePermission)
 * → 权限(Permission)。</p>
 *
 * <p><b>超管语义</b>：用户只要持有一个启用且 {@code is_super = 1} 的角色，即视为超级管理员，
 * 权限列表直接返回通配 {@code *:*:*}（不再要求 sys_role_permission 里绑定一条 *:*:* 记录）。
 * 前端菜单过滤与后端校验都按 {@code *:*:*} 放行，语义与重构前一致。</p>
 *
 * <p><b>缓存</b>：列表由 {@link PermissionCache} 缓存进账号会话，按全局权限版本失效，
 * 避免每次权限校验都查 4 张表；角色/权限变更后由管理端调用 {@code PermissionCache#bumpVersion()}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionMapper permissionMapper;
    private final PermissionCache permissionCache;

    /**
     * 获取用户权限编码列表。
     *
     * <p>命中超管角色时短路返回 {@code *:*:*}，不再查权限表。</p>
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        if (loginId == null) {
            return List.of();
        }
        return permissionCache.getPermissions(loginId, () -> loadPermissions(loginId));
    }

    /** 获取用户角色编码列表（仅启用角色） */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return List.of();
        }
        return permissionCache.getRoles(loginId, () -> loadRoles(loginId));
    }

    // ==================== 回源查询 ====================

    private List<String> loadPermissions(Object loginId) {
        Long userId = parseLoginId(loginId);
        if (userId == null) {
            return List.of();
        }
        List<Role> roles = enabledRoles(userId);
        if (roles.isEmpty()) {
            return List.of();
        }
        if (isSuper(roles)) {
            return List.of(PermissionChecker.SUPER_PERMISSION);
        }
        List<Long> permissionIds = permissionIds(roles.stream().map(Role::getId).collect(Collectors.toList()));
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectList(
                new QueryWrapper<Permission>().in("id", permissionIds).eq("status", 1)
        ).stream().map(Permission::getPermissionCode).collect(Collectors.toList());
    }

    private List<String> loadRoles(Object loginId) {
        Long userId = parseLoginId(loginId);
        if (userId == null) {
            return List.of();
        }
        return enabledRoles(userId).stream().map(Role::getRoleCode).collect(Collectors.toList());
    }

    /** 用户持有的启用角色 */
    private List<Role> enabledRoles(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(
                new QueryWrapper<UserRole>().eq("user_id", userId)
        ).stream().map(UserRole::getRoleId).collect(Collectors.toList());
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectList(new QueryWrapper<Role>().in("id", roleIds).eq("status", 1));
    }

    /** 是否持有任一起管角色 */
    private boolean isSuper(List<Role> roles) {
        return roles.stream().anyMatch(r -> r.getIsSuper() != null && r.getIsSuper() == 1);
    }

    /** 用户全部启用角色关联的权限ID（去重） */
    private List<Long> permissionIds(List<Long> roleIds) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return rolePermissionMapper.selectList(
                new QueryWrapper<RolePermission>().in("role_id", roleIds)
        ).stream().map(RolePermission::getPermissionId).distinct().collect(Collectors.toList());
    }

    private Long parseLoginId(Object loginId) {
        try {
            return Long.valueOf(loginId.toString());
        } catch (NumberFormatException e) {
            log.warn("非法 loginId: {}", loginId);
            return null;
        }
    }
}
