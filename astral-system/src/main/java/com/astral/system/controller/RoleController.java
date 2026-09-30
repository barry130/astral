package com.astral.system.controller;

import com.astral.auth.security.PermissionCache;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 角色管理控制器
 * <p>提供角色CRUD操作、角色权限查询与分配功能</p>
 * <p>权限：查询类接口要求 {@code admin:system:role:view}，写接口与权限分配为提权面，要求超管（{@link RequiresSuper}）</p>
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
    /** 权限缓存：角色/角色权限关系变更后递增版本，立即失效所有用户的权限缓存 */
    private final PermissionCache permissionCache;

    /**
     * 分页查询角色列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页角色数据
     */
    @Operation(summary = "分页查询")
    @RequiresPermission("admin:system:role:view")
    @GetMapping("/page")
    public Result<Page<Role>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                   @RequestParam(defaultValue = "10") Integer pageSize) {
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
    @RequiresPermission("admin:system:role:view")
    @GetMapping("/{id}")
    public Result<Role> getById(@PathVariable Long id) {
        return Result.success(roleService.getById(id));
    }

    /**
     * 创建新角色
     *
     * @param entity 角色实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    // 保持超管专属：请求体直接是实体，客户端可传 isSuper=1 造出超管角色，
    // 属于提权入口，不能下放给 admin:system:role:edit
    @RequiresSuper
    @PostMapping
    public Result<Void> create(@RequestBody Role entity) {
        // 双保险：新增角色一律非超管，超管身份只能在库里显式设置
        entity.setIsSuper(0);
        roleService.save(entity);
        // 新增角色会影响角色列表/权限映射，递增权限版本
        permissionCache.bumpVersion();
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
    // 可委派：只改角色名称/描述/排序/状态等元信息。
    // is_super 是提权开关，不可由此入口改写，否则持有 admin:system:role:edit 的人只要
    // PUT {"isSuper":1} 就能把自己变成超管。
    @RequiresPermission(value = "admin:system:role:edit", name = "角色维护", domain = "system",
            description = "角色的新增/修改/删除（分配权限与 is_super 变更仍仅限超管）")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Role entity) {
        if (roleService.getById(id) == null) {
            throw new BusinessException("SYS003");
        }
        // 白名单 + 按需更新：只允许改这几个字段，且只写客户端真正传了的字段。
        // 不能用 updateById(entity) —— 实测 MP 3.5.17 会生成
        //   UPDATE sys_role SET role_name=?, is_super=?, update_time=? WHERE id=?
        // 即便 is_super 置 null 也会被写进 SET 子句（等于给持有 admin:system:role:edit 的人留了自封超管的后门）；
        // 而「先查后覆盖」也不可靠（读会被会话缓存命中，拿到的可能是旧值）。
        // 因此统一走 lambdaUpdate 白名单；且必须逐字段判 null，否则未传的字段会被写成
        // NULL 而撞上 status 的 not-null 约束（局部更新场景，前端只传改动字段）。
        var wrapper = roleService.lambdaUpdate().eq(Role::getId, id);
        if (entity.getRoleName() != null) {
            wrapper.set(Role::getRoleName, entity.getRoleName());
        }
        if (entity.getDescription() != null) {
            wrapper.set(Role::getDescription, entity.getDescription());
        }
        if (entity.getStatus() != null) {
            wrapper.set(Role::getStatus, entity.getStatus());
        }
        if (entity.getSort() != null) {
            wrapper.set(Role::getSort, entity.getSort());
        }
        wrapper.set(Role::getUpdateTime, LocalDateTime.now());
        if (!wrapper.update()) {
            throw new BusinessException("SYS003");
        }
        // 角色状态变更会改变权限解析结果，递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 删除角色
     *
     * @param id 角色ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    // 可委派：删除角色只做引用校验与级联清理，不涉及提权
    @RequiresPermission("admin:system:role:edit")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        // 引用校验 + 级联清理权限关联 + 删除角色，整体在一个事务里
        // （见 RoleServiceImpl.deleteRoleWithRelations）
        roleService.deleteRoleWithRelations(id);
        // 角色及其权限关联已删除，递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 查询所有角色（不分页）
     *
     * @return 全部角色列表
     */
    @Operation(summary = "查询所有角色")
    @RequiresPermission("admin:system:role:view")
    @GetMapping("/all")
    public Result<List<Role>> getAll() {
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
    @RequiresPermission("admin:system:role:view")
    @GetMapping("/{id}/permissions")
    public Result<List<Permission>> getRolePermissions(@PathVariable Long id) {
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
    // 提权类操作：给角色加 `*:*:*` 再套到自己身上 = 提权，只允许超级管理员
    @RequiresSuper
    @PutMapping("/{id}/permissions")
    public Result<Void> assignPermissions(@PathVariable Long id, @RequestBody Map<String, List<Long>> body) {
        if (roleService.getById(id) == null) {
            return Result.fail("角色不存在");
        }
        // 先删后插 + 权限存在性校验，整体在一个事务里（见 RolePermissionServiceImpl.assignPermissions）
        rolePermissionService.assignPermissions(id, body.get("permissionIds"));
        // 角色→权限映射已变更，递增权限版本让所有用户的权限缓存下次读取即回源
        permissionCache.bumpVersion();
        return Result.success();
    }
}
