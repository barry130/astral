package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMenu;
import com.astral.system.service.SysMenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/system/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuService sysMenuService;

    @RequiresPermission("admin:system:menu:view")
    @GetMapping("/tree")
    public Result<List<Map<String, Object>>> getMenuTree() {
        List<SysMenu> all = sysMenuService.listAll();
        List<Map<String, Object>> tree = buildTree(all, 0L);
        return Result.success(tree);
    }

    @RequiresPermission("admin:system:menu:view")
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getMenuList() {
        List<SysMenu> all = sysMenuService.listAll();
        List<Map<String, Object>> result = all.stream().map(this::toMap).collect(Collectors.toList());
        return Result.success(result);
    }

    @RequiresPermission("admin:system:menu:view")
    @GetMapping("/{id}")
    public Result<SysMenu> getById(@PathVariable Long id) {
        return Result.success(sysMenuService.listAll().stream()
                .filter(m -> id.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new com.astral.common.exception.BusinessException("SYS013")));
    }

    // 菜单行本身不授予任何权限（可见性还受各自 node.permission 约束），
    // 因此维护菜单属于可委派的配置类操作，要求 admin:system:menu:edit
    @RequiresPermission(value = "admin:system:menu:edit", name = "菜单维护", domain = "system",
            description = "管理端菜单树的新增/修改/删除")
    @PostMapping
    public Result<SysMenu> create(@RequestBody SysMenu menu) {
        return Result.success(sysMenuService.create(menu));
    }

    @RequiresPermission("admin:system:menu:edit")
    @PutMapping("/{id}")
    public Result<SysMenu> update(@PathVariable Long id, @RequestBody SysMenu menu) {
        return Result.success(sysMenuService.update(id, menu));
    }

    /**
     * 删除菜单（级联子孙，单事务）
     * <p>原实现写在 Controller 里，只删一层子菜单且两条 delete 不在同一事务：
     * 第二条失败会留下 parent_id 悬空的孤儿节点，且孙级菜单永远删不掉。</p>
     */
    @RequiresPermission("admin:system:menu:edit")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysMenuService.deleteWithChildren(id);
        return Result.success();
    }

    private List<Map<String, Object>> buildTree(List<SysMenu> all, Long parentId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SysMenu menu : all) {
            if (!parentId.equals(menu.getParentId())) continue;
            Map<String, Object> node = toMap(menu);
            List<Map<String, Object>> children = buildTree(all, menu.getId());
            if (!children.isEmpty()) node.put("children", children);
            result.add(node);
        }
        return result;
    }

    private Map<String, Object> toMap(SysMenu menu) {
        Map<String, Object> map = new HashMap<>();
        map.put("id", menu.getId());
        map.put("parentId", menu.getParentId());
        map.put("name", menu.getName());
        map.put("icon", menu.getIcon());
        map.put("path", menu.getPath());
        map.put("permission", menu.getPermission());
        map.put("sort", menu.getSort());
        map.put("visible", menu.getVisible());
        map.put("type", menu.getType());
        return map;
    }
}
