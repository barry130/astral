package com.astral.server.controller;

import com.astral.dao.entity.RolePermission;
import com.astral.server.service.RolePermissionService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 角色权限关联控制器
 * <p>提供角色权限关联关系的CRUD操作</p>
 */
@Tag(name = "角色权限关联表")
@RestController
@RequestMapping("/api/v1/system/role_permission")
@RequiredArgsConstructor
public class RolePermissionController {

    /** 角色权限关联服务 */
    private final RolePermissionService rolePermissionService;

    /**
     * 分页查询角色权限关联列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页角色权限关联数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<RolePermission>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<RolePermission> page = new Page<>(pageNum, pageSize);
        return Result.success(rolePermissionService.page(page));
    }

    /**
     * 根据ID查询角色权限关联详情
     *
     * @param id 关联记录ID
     * @return 角色权限关联实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<RolePermission> getById(@PathVariable Long id) {
        return Result.success(rolePermissionService.getById(id));
    }

    /**
     * 创建角色权限关联
     *
     * @param entity 角色权限关联实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody RolePermission entity) {
        rolePermissionService.save(entity);
        return Result.success();
    }

    /**
     * 更新角色权限关联
     *
     * @param id 关联记录ID
     * @param entity 角色权限关联实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody RolePermission entity) {
        entity.setId(id);
        rolePermissionService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除角色权限关联
     *
     * @param id 关联记录ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        rolePermissionService.removeById(id);
        return Result.success();
    }
}
