package com.astral.system.service.impl;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.service.RolePermissionService;
import com.astral.system.service.RoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供角色实体的标准CRUD操作</p>
 *
 * <p>引用统计直接走 {@link UserRoleMapper}：{@code UserRoleServiceImpl} 需要反向校验
 * 角色是否存在，互相注入服务会形成 Bean 循环依赖。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleServiceImpl extends ServiceImpl<RoleMapper, Role> implements RoleService {

    private final UserRoleMapper userRoleMapper;
    private final RolePermissionService rolePermissionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRoleWithRelations(Long roleId) {
        Long refs = userRoleMapper.selectCount(
                new LambdaQueryWrapper<UserRole>().eq(UserRole::getRoleId, roleId));
        long userRefs = refs == null ? 0L : refs;
        if (userRefs > 0) {
            log.warn("删除角色被拒绝：仍被用户引用, roleId={}, 引用数={}", roleId, userRefs);
            throw new BusinessException("SYS006", userRefs);
        }
        // 清理权限关联与角色本身，同事务
        rolePermissionService.remove(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));
        removeById(roleId);
    }
}
