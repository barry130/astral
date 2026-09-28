package com.astral.system.service;

import com.astral.dao.entity.UserRole;
import com.baomidou.mybatisplus.spring.service.IService;

import java.util.List;

/**
 * 用户角色关联服务接口
 * <p>继承MyBatis-Plus的IService，提供用户角色关联实体的标准CRUD操作</p>
 */
public interface UserRoleService extends IService<UserRole> {

    /**
     * 为用户重新分配角色（先清空、再写入，整体原子）。
     *
     * <p><b>为什么必须走这个方法，而不是在 Controller 里 remove + saveBatch</b>：
     * 两步之间失败会留下「角色已被清空、新角色没写进去」的中间态，
     * 用户会失去全部权限且无法自愈。必须放在同一事务里。</p>
     *
     * @param userId  用户ID
     * @param roleIds 目标角色ID列表；{@code null} 或空表示清空该用户全部角色
     * @throws com.astral.common.exception.BusinessException 存在不存在的角色ID时
     */
    void assignRoles(Long userId, List<Long> roleIds);
}
