package com.astral.system.controller;

import com.astral.common.error.ErrorCodes;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.Permission;
import com.astral.dao.entity.RolePermission;
import com.astral.dao.mapper.RolePermissionMapper;
import com.astral.system.service.PermissionService;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "权限管理")
@RestController
@RequestMapping("/api/v1/system/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final RolePermissionMapper rolePermissionMapper;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<Permission>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Permission> page = new Page<>(pageNum, pageSize);
        return Result.success(permissionService.page(page));
    }

    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<Permission> getById(@PathVariable Long id) {
        return Result.success(permissionService.getById(id));
    }

    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody Permission entity) {
        permissionService.save(entity);
        return Result.success();
    }

    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Permission entity) {
        entity.setId(id);
        permissionService.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        QueryWrapper<RolePermission> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("permission_id", id);
        long count = rolePermissionMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException("SYS006", count);
        }
        permissionService.removeById(id);
        return Result.success();
    }

    @Operation(summary = "权限树")
    @GetMapping("/tree")
    public Result<List<Permission>> getTree() {
        List<Permission> all = permissionService.list();
        Map<Long, List<Permission>> groupByParent = all.stream()
                .filter(p -> p.getParentId() != null)
                .collect(Collectors.groupingBy(Permission::getParentId));

        List<Permission> tree = all.stream()
                .filter(p -> p.getParentId() == null || p.getParentId() == 0)
                .peek(root -> setChildren(root, groupByParent))
                .sorted((p1, p2) -> Integer.compare(
                    p1.getSort() != null ? p1.getSort() : 0,
                    p2.getSort() != null ? p2.getSort() : 0
                ))
                .collect(Collectors.toList());

        return Result.success(tree);
    }

    private void setChildren(Permission parent, Map<Long, List<Permission>> groupByParent) {
        List<Permission> children = groupByParent.getOrDefault(parent.getId(), new ArrayList<>());
        if (!children.isEmpty()) {
            children.sort((c1, c2) -> Integer.compare(
                c1.getSort() != null ? c1.getSort() : 0,
                c2.getSort() != null ? c2.getSort() : 0
            ));
            parent.setChildren(children);
            children.forEach(child -> setChildren(child, groupByParent));
        }
    }
}
