package com.astral.system.controller;

import com.astral.auth.security.PermissionChecker;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.Role;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.entity.UserRole;
import com.astral.system.service.PermissionService;
import com.astral.system.service.RolePermissionService;
import com.astral.system.service.RoleService;
import com.astral.system.service.UserRoleService;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色管理控制器
 * <p>提供角色CRUD操作、角色权限查询与分配功能</p>
 */
@Tag(name = "角色管理")
@RestController
@RequestMapping("/api/v1/admin/system/role")
@RequiredArgsConstructor
public class RoleController {

    /** 角色服务 */
    private final RoleService roleService;
    /** 角色权限关联服务 */
    private final RolePermissionService rolePermissionService;
    /** 权限服务 */
    private final PermissionService permissionService;
    /** 用户角色关联服务：删除角色前校验引用，避免留下孤儿授权 */
    private final UserRoleService userRoleService;
    /** 权限校验器：管理端接口按 RBAC 权限编码校验 */
    private final PermissionChecker permissionChecker;

    /**
     * 分页查询角色列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页角色数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<Role>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                   @RequestParam(defaultValue = "10") Integer pageSize) {
        permissionChecker.require("system:role:view");
        Page<Role> page = new Page<>(pageNum, pageSize);
        return Result.success(roleService.page(page));
    }

    /**
     * 根据ID查询角色详情
     *
     * @param id 角色ID
     * @return 角色实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<Role> getById(@PathVariable Long id) {
        permissionChecker.require("system:role:view");
        return Result.success(roleService.getById(id));
    }

    /**
     * 创建新角色
     *
     * @param entity 角色实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody Role entity) {
        permissionChecker.requireSuper();
        roleService.save(entity);
        return Result.success();
    }

    /**
     * 更新角色信息
     *
     * @param id 角色ID
     * @param entity 角色实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Role entity) {
        permissionChecker.requireSuper();
        entity.setId(id);
        // 防止 mass assignment：这些字段不可由客户端改写。
        // 只显式置 null 依赖"MP 默认跳过 null"这一策略，比让整个实体原样落库安全得多。
        entity.setCreateTime(null);
        entity.setUpdateTime(null);
        roleService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除角色
     *
     * @param id 角色ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionChecker.requireSuper();
        // 引用校验 + 级联清理权限关联 + 删除角色，整体在一个事务里
        // （见 RoleServiceImpl.deleteRoleWithRelations）
        roleService.deleteRoleWithRelations(id);
        return Result.success();
    }

    /**
     * 查询所有角色（不分页）
     *
     * @return 全部角色列表
     */
    @Operation(summary = "查询所有角色")
    @GetMapping("/all")
    public Result<List<Role>> getAll() {
        permissionChecker.require("system:role:view");
        return Result.success(roleService.list());
    }

    /**
     * 获取指定角色的所有权限
     * <p>通过角色权限关联表查询角色关联的权限ID，再批量查询权限详情</p>
     *
     * @param id 角色ID
     * @return 角色权限列表
     */
    @Operation(summary = "获取角色权限")
    @GetMapping("/{id}/permissions")
    public Result<List<Permission>> getRolePermissions(@PathVariable Long id) {
        permissionChecker.require("system:role:view");
        List<RolePermission> rolePermissions = rolePermissionService.list(new LambdaQueryWrapper<RolePermission>().eq(RolePermission::getRoleId, id));
        if (rolePermissions.isEmpty()) {
            return Result.success(List.of());
        }
        // 提取权限ID列表，批量查询权限详情
        List<Long> permissionIds = rolePermissions.stream().map(RolePermission::getPermissionId).collect(Collectors.toList());
        List<Permission> permissions = permissionService.listByIds(permissionIds);
        return Result.success(permissions);
    }

    /**
     * 为角色分配权限
     * <p>先删除角色现有的所有权限关联，再批量插入新的权限关联</p>
     *
     * @param id 角色ID
     * @param body 请求体，包含permissionIds字段（权限ID列表）
     * @return 操作结果
     */
    @Operation(summary = "分配角色权限")
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        // 提权类操作：给角色加 `*:*:*` 再套到自己身上 = 提权，只允许超级管理员
        permissionChecker.requireSuper();
        if (roleService.getById(id) == null) {
            return Result.fail("角色不存在");
        }
        // 先删后插 + 权限存在性校验，整体在一个事务里（见 RolePermissionServiceImpl.assignPermissions）
        rolePermissionService.assignPermissions(id, body.get("permissionIds"));
        return Result.success();
    }
}
