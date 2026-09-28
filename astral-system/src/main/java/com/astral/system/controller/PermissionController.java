package com.astral.system.controller;

import com.astral.auth.security.PermissionChecker;
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

import jakarta.validation.Valid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Tag(name = "权限管理")
@RestController
@RequestMapping("/api/v1/admin/system/permission")
@RequiredArgsConstructor
public class PermissionController {

    /** 查看权限（sys_permission: system:permission:view），与前端 layout.tsx 的菜单权限一致 */
    private static final String PERM_VIEW = "system:permission:view";

    private final PermissionService permissionService;
    private final RolePermissionMapper rolePermissionMapper;
    private final PermissionChecker permissionChecker;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<Permission>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize) {
        permissionChecker.require(PERM_VIEW);
        Page<Permission> page = new Page<>(pageNum, pageSize);
        return Result.success(permissionService.page(page));
    }

    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<Permission> getById(@PathVariable Long id) {
        permissionChecker.require(PERM_VIEW);
        return Result.success(permissionService.getById(id));
    }

    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody Permission entity) {
        // 权限决定接口可达性，属于提权面，要求超管
        permissionChecker.requireSuper();
        permissionService.save(entity);
        return Result.success();
    }

    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Permission entity) {
        permissionChecker.requireSuper();
        entity.setId(id);
        // 防止 mass assignment：时间列不可由客户端改写
        entity.setCreateTime(null);
        entity.setUpdateTime(null);
        permissionService.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionChecker.requireSuper();
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
        permissionChecker.require(PERM_VIEW);
        List<Permission> all = permissionService.list();
        Map<Long, List<Permission>> groupByParent = all.stream()
                .filter(p -> p.getParentId() != null)
                .collect(Collectors.groupingBy(Permission::getParentId));

        // 原实现用 stream.peek() 承载副作用（挂 children）：peek 的定位是调试，
        // 一旦上游加了短路操作（findFirst/limit）就不会对所有元素执行，属于隐患。
        // 这里改成先收集再显式遍历。
        List<Permission> roots = all.stream()
                .filter(p -> p.getParentId() == null || p.getParentId() == 0)
                .sorted(Comparator.comparingInt(p -> p.getSort() != null ? p.getSort() : 0))
                .collect(Collectors.toList());
        // 环检测：库里若出现 A.parent=B、B.parent=A 这类脏数据，
        // 原递归会无限下钻直至 StackOverflowError，整个权限管理页 500。
        Set<Long> visiting = new HashSet<>();
        for (Permission root : roots) {
            setChildren(root, groupByParent, visiting);
        }
        return Result.success(roots);
    }

    /**
     * 递归挂载子节点
     *
     * @param parent         当前节点
     * @param groupByParent  父ID → 子节点列表
     * @param visiting       当前递归路径上的节点ID，用于环检测
     */
    private void setChildren(Permission parent, Map<Long, List<Permission>> groupByParent, Set<Long> visiting) {
        if (parent == null || parent.getId() == null || !visiting.add(parent.getId())) {
            // 命中环或空ID：停止下钻，避免栈溢出
            return;
        }
        List<Permission> children = groupByParent.getOrDefault(parent.getId(), new ArrayList<>());
        if (!children.isEmpty()) {
            children.sort(Comparator.comparingInt(c -> c.getSort() != null ? c.getSort() : 0));
            parent.setChildren(children);
            for (Permission child : children) {
                setChildren(child, groupByParent, visiting);
            }
        }
        visiting.remove(parent.getId());
    }
}
