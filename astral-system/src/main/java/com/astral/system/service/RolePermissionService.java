package com.astral.system.service;

import com.astral.dao.entity.RolePermission;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

/**
 * 角色权限关联服务接口
 * <p>继承MyBatis-Plus的IService，提供角色权限关联实体的标准CRUD操作</p>
 */
public interface RolePermissionService extends IService<RolePermission> {

    /**
     * 为角色重新分配权限（先清空、再写入，整体原子）。
     *
     * <p>与 {@link UserRoleService#assignRoles} 同理：两步操作必须同事务，
     * 否则中途失败会让角色权限被清空且无法恢复。</p>
     *
     * @param roleId        角色ID
     * @param permissionIds 目标权限ID列表；{@code null} 或空表示清空该角色全部权限
     * @throws com.astral.common.exception.BusinessException 存在不存在的权限ID时
     */
    void assignPermissions(Long roleId, List<Long> permissionIds);
}
