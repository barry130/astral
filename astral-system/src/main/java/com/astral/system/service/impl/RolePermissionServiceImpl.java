package com.astral.system.service.impl;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.system.service.PermissionService;
import com.astral.system.service.RolePermissionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 角色权限关联服务实现类
 * <p>继承MyBatis-Plus的ServiceImpl，提供角色权限关联实体的标准CRUD操作</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RolePermissionServiceImpl extends ServiceImpl<RolePermissionMapper, RolePermission>
        implements RolePermissionService {

    private final PermissionService permissionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        List<Long> distinct = (permissionIds == null || permissionIds.isEmpty())
                ? List.of()
                : permissionIds.stream().filter(Objects::nonNull).distinct().toList();

        if (!distinct.isEmpty()) {
            long found = permissionService.count(
                    new LambdaQueryWrapper<Permission>().in(Permission::getId, distinct));
            if (found != distinct.size()) {
                log.warn("分配权限失败：存在不存在的权限ID, roleId={}, 期望={}, 实际={}",
                        roleId, distinct.size(), found);
                throw new BusinessException("SYS012", distinct.size() - found);
            }
        }

        remove(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));

        if (!distinct.isEmpty()) {
            List<RolePermission> rows = new ArrayList<>(distinct.size());
            for (Long permissionId : distinct) {
                RolePermission rp = new RolePermission();
                rp.setRoleId(roleId);
                rp.setPermissionId(permissionId);
                rows.add(rp);
            }
            saveBatch(rows);
        }
    }
}
