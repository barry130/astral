package com.astral.server.controller;

import com.astral.dao.entity.Permission;
import com.astral.server.service.PermissionService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 权限管理控制器
 * <p>提供权限CRUD操作及权限树形结构查询功能</p>
 */
@Tag(name = "权限管理")
@RestController
@RequestMapping("/api/v1/system/permission")
@RequiredArgsConstructor
public class PermissionController {

    /** 权限服务 */
    private final PermissionService permissionService;

    /**
     * 分页查询权限列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页权限数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<Permission>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Permission> page = new Page<>(pageNum, pageSize);
        return Result.success(permissionService.page(page));
    }

    /**
     * 根据ID查询权限详情
     *
     * @param id 权限ID
     * @return 权限实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<Permission> getById(@PathVariable Long id) {
        return Result.success(permissionService.getById(id));
    }

    /**
     * 创建新权限
     *
     * @param entity 权限实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody Permission entity) {
        permissionService.save(entity);
        return Result.success();
    }

    /**
     * 更新权限信息
     *
     * @param id 权限ID
     * @param entity 权限实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Permission entity) {
        entity.setId(id);
        permissionService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除权限
     *
     * @param id 权限ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionService.removeById(id);
        return Result.success();
    }

    /**
     * 获取权限树形结构
     * <p>将扁平的权限列表转换为树形结构，parentId为null或0的作为根节点</p>
     *
     * @return 权限树
     */
    @Operation(summary = "权限树")
    @GetMapping("/tree")
    public Result<List<Permission>> getTree() {
        List<Permission> all = permissionService.list();
        // 按parentId分组，用于快速查找子节点
        Map<Long, List<Permission>> groupByParent = all.stream()
                .filter(p -> p.getParentId() != null)
                .collect(Collectors.groupingBy(Permission::getParentId));

        // 筛选根节点（parentId为null或0），递归设置子节点
        List<Permission> tree = all.stream()
                .filter(p -> p.getParentId() == null || p.getParentId() == 0)
                .peek(root -> setChildren(root, groupByParent))
                .collect(Collectors.toList());

        return Result.success(tree);
    }

    /**
     * 递归设置权限的子节点
     *
     * @param parent 父权限节点
     * @param groupByParent 按parentId分组的权限映射
     */
    private void setChildren(Permission parent, Map<Long, List<Permission>> groupByParent) {
        List<Permission> children = groupByParent.getOrDefault(parent.getId(), new ArrayList<>());
        if (!children.isEmpty()) {
            parent.setChildren(children);
            // 递归为每个子节点设置其子节点
            children.forEach(child -> setChildren(child, groupByParent));
        }
    }
}
