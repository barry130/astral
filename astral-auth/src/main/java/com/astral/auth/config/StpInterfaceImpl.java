package com.astral.auth.config;

import cn.dev33.satoken.stp.StpInterface;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.UserRole;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.Permission;
import com.astral.dao.mapper.RolePermissionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sa-Token 权限接口实现
 * <p>
 * 实现 Sa-Token 的 {@link StpInterface} 接口，为Sa-Token提供用户的角色和权限数据。
 * Sa-Token在进行权限校验时会调用此实现类的方法获取用户的角色列表和权限列表。
 * </p>
 * <p>
 * 权限模型：用户(User) -> 用户角色关联(UserRole) -> 角色(Role) -> 角色权限关联(RolePermission) -> 权限(Permission)
 * </p>
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    /** 用户角色关联Mapper，用于查询用户关联的角色ID列表 */
    @Autowired
    private UserRoleMapper userRoleMapper;

    /** 角色Mapper，用于查询角色详情 */
    @Autowired
    private RoleMapper roleMapper;

    /** 角色权限关联Mapper，用于查询角色关联的权限ID列表 */
    @Autowired
    private RolePermissionMapper rolePermissionMapper;

    /** 权限Mapper，用于查询权限详情 */
    @Autowired
    private PermissionMapper permissionMapper;

    /**
     * 获取用户的权限编码列表
     * <p>
     * 查询流程：
     * 1. 根据用户ID查询所有关联的角色ID
     * 2. 根据角色ID列表查询所有关联的权限ID
     * 3. 根据权限ID列表查询权限详情，过滤状态为启用的权限
     * 4. 提取权限编码返回
     * </p>
     *
     * @param loginId   登录用户ID
     * @param loginType 登录类型（Sa-Token参数，本项目中未使用）
     * @return 权限编码列表
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());

        List<Long> roleIds = userRoleMapper.selectList(
                new QueryWrapper<UserRole>().eq("user_id", userId)
        ).stream().map(UserRole::getRoleId).collect(Collectors.toList());

        if (roleIds.isEmpty()) {
            return List.of();
        }

        List<Long> permissionIds = rolePermissionMapper.selectList(
                new QueryWrapper<RolePermission>().in("role_id", roleIds)
        ).stream().map(RolePermission::getPermissionId).collect(Collectors.toList());

        if (permissionIds.isEmpty()) {
            return List.of();
        }

        return permissionMapper.selectList(
                new QueryWrapper<Permission>().in("id", permissionIds).eq("status", 1)
        ).stream().map(Permission::getPermissionCode).collect(Collectors.toList());
    }

    /**
     * 获取用户的角色编码列表
     * <p>
     * 查询流程：
     * 1. 根据用户ID查询所有关联的角色ID
     * 2. 根据角色ID列表查询角色详情，过滤状态为启用的角色
     * 3. 提取角色编码返回
     * </p>
     *
     * @param loginId   登录用户ID
     * @param loginType 登录类型（Sa-Token参数，本项目中未使用）
     * @return 角色编码列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        Long userId = Long.valueOf(loginId.toString());

        List<Long> roleIds = userRoleMapper.selectList(
                new QueryWrapper<UserRole>().eq("user_id", userId)
        ).stream().map(UserRole::getRoleId).collect(Collectors.toList());

        if (roleIds.isEmpty()) {
            return List.of();
        }

        return roleMapper.selectList(
                new QueryWrapper<Role>().in("id", roleIds).eq("status", 1)
        ).stream().map(Role::getRoleCode).collect(Collectors.toList());
    }
}