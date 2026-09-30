package com.astral.system.controller;

import com.astral.auth.security.PermissionCache;
import com.astral.common.annotation.RequiresSuper;
import com.astral.dao.entity.UserRole;
import com.astral.system.service.UserRoleService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * 用户角色关联控制器
 * <p>提供用户角色关联关系的CRUD操作</p>
 * <p>权限：本控制器整体属于「用户 → 角色」的提权面（读写都会暴露或改变授权关系），
 * 全部接口要求超管（类级 {@link RequiresSuper}）。</p>
 */
@Tag(name = "用户角色关联表")
@RestController
@RequestMapping("/api/v1/admin/system/user_role")
@RequiresSuper
@RequiredArgsConstructor
public class UserRoleController {

    /** 用户角色关联服务 */
    private final UserRoleService userRoleService;
    /** 权限缓存：用户角色关系变更后递增版本，立即失效所有用户的权限缓存 */
    private final PermissionCache permissionCache;

    /**
     * 分页查询用户角色关联列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页用户角色关联数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<UserRole>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<UserRole> page = new Page<>(pageNum, pageSize);
        return Result.success(userRoleService.page(page));
    }

    /**
     * 根据ID查询用户角色关联详情
     *
     * @param id 关联记录ID
     * @return 用户角色关联实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<UserRole> getById(@PathVariable Long id) {
        return Result.success(userRoleService.getById(id));
    }

    /**
     * 创建用户角色关联
     *
     * @param entity 用户角色关联实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody UserRole entity) {
        userRoleService.save(entity);
        // 用户→角色映射已变更，递增权限版本让所有用户的权限缓存下次读取即回源
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 更新用户角色关联
     *
     * @param id 关联记录ID
     * @param entity 用户角色关联实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody UserRole entity) {
        entity.setId(id);
        userRoleService.updateById(entity);
        // 用户→角色映射已变更，递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 删除用户角色关联
     *
     * @param id 关联记录ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        userRoleService.removeById(id);
        // 用户→角色映射已变更，递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }
}
