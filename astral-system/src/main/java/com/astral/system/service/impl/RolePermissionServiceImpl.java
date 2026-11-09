package com.astral.system.service.impl;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.system.service.PermissionService;
import com.astral.system.service.RolePermissionService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final RoleMapper roleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignPermissions(Long roleId, List<Long> permissionIds) {
        lockRoles();
        requireRole(roleId);
        List<Long> distinct = (permissionIds == null || permissionIds.isEmpty())
                ? List.of()
                : permissionIds.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .sorted()
                        .toList();

        if (!distinct.isEmpty()) {
            long found = permissionService
                    .list(new LambdaQueryWrapper<Permission>()
                            .in(Permission::getId, distinct)
                            .orderByAsc(Permission::getId)
                            .last("FOR UPDATE"))
                    .size();
            if (found != distinct.size()) {
                log.warn("分配权限失败：存在不存在的权限ID, roleId={}, 期望={}, 实际={}", roleId, distinct.size(), found);
                throw new BusinessException("SYS012", distinct.size() - found);
            }
        }

        // Roles are already locked. Never reacquire an expanded role set after locking permissions.
        super.remove(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, roleId));

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

    private void lockRoles() {
        roleMapper.selectList(
                new LambdaQueryWrapper<Role>().orderByAsc(Role::getId).last("FOR UPDATE"));
    }

    private void requireRole(Long roleId) {
        if (roleId == null || roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getId, roleId)) == 0) {
            throw new BusinessException("SYS003");
        }
    }

    private void requirePermission(Long permissionId) {
        if (permissionId == null
                || permissionService
                        .list(new LambdaQueryWrapper<Permission>()
                                .eq(Permission::getId, permissionId)
                                .last("FOR UPDATE"))
                        .isEmpty()) {
            throw new BusinessException("SYS005");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(RolePermission row) {
        lockRoles();
        requireRole(row.getRoleId());
        requirePermission(row.getPermissionId());
        return super.save(row);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(RolePermission row) {
        return updateAssignment(row.getId(), row.getRoleId(), row.getPermissionId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        lockRoles();
        return super.removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Wrapper<RolePermission> wrapper) {
        lockRoles();
        return super.remove(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateAssignment(Long id, Long roleId, Long permissionId) {
        if (roleId == null && permissionId == null) return false;
        lockRoles();
        RolePermission existing = getById(id);
        if (existing == null) return false;
        requireRole(roleId == null ? existing.getRoleId() : roleId);
        requirePermission(permissionId == null ? existing.getPermissionId() : permissionId);
        return lambdaUpdate()
                .eq(RolePermission::getId, id)
                .set(roleId != null, RolePermission::getRoleId, roleId)
                .set(permissionId != null, RolePermission::getPermissionId, permissionId)
                .update();
    }
}
