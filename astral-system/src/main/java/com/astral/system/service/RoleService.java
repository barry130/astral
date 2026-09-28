package com.astral.system.service;

import com.astral.dao.entity.Role;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 角色服务接口
 * <p>继承MyBatis-Plus的IService，提供角色实体的标准CRUD操作</p>
 */
public interface RoleService extends IService<Role> {

    /**
     * 删除角色，并级联清理其权限关联（整体原子）。
     *
     * <p><b>策略与 {@code PermissionController} 保持一致</b>：删除前先查引用，
     * 若仍有用户关联该角色则拒绝删除。原实现直接 {@code removeById}，
     * 会留下 {@code sys_user_role} / {@code sys_role_permission} 孤儿行——
     * 用户之后重新获得同名角色时，可能「继承」到本不该有的权限。</p>
     *
     * @param roleId 角色ID
     * @throws com.astral.common.exception.BusinessException 该角色仍被用户引用时
     */
    void deleteRoleWithRelations(Long roleId);
}
