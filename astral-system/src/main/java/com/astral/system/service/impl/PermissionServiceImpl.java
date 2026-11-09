package com.astral.system.service.impl;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.system.service.PermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供权限实体的标准CRUD操作</p>
 */
@Service
@RequiredArgsConstructor
public class PermissionServiceImpl extends ServiceImpl<PermissionMapper, Permission> implements PermissionService {
    private final RolePermissionMapper rolePermissionMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteIfUnassigned(Long id) {
        // Grants lock roles before permissions. Deletion needs only this permission, never a later role lock.
        if (list(new LambdaQueryWrapper<Permission>().eq(Permission::getId, id).last("FOR UPDATE"))
                .isEmpty()) return;
        long count = rolePermissionMapper.selectCount(
                new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getPermissionId, id));
        if (count > 0) throw new BusinessException("SYS006", count);
        removeById(id);
    }
}
