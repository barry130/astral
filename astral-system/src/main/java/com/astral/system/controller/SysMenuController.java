package com.astral.system.controller;

import com.astral.auth.security.PermissionChecker;
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
    private final PermissionChecker permissionChecker;

    @GetMapping("/tree")
    public Result<List<Map<String, Object>>> getMenuTree() {
        permissionChecker.require("system:menu:view");
        List<SysMenu> all = sysMenuService.listAll();
        List<Map<String, Object>> tree = buildTree(all, 0L);
        return Result.success(tree);
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getMenuList() {
        permissionChecker.require("system:menu:view");
        List<SysMenu> all = sysMenuService.listAll();
        List<Map<String, Object>> result = all.stream().map(this::toMap).collect(Collectors.toList());
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<SysMenu> getById(@PathVariable Long id) {
        permissionChecker.require("system:menu:view");
        return Result.success(sysMenuService.listAll().stream()
                .filter(m -> id.equals(m.getId()))
                .findFirst()
                .orElseThrow(() -> new com.astral.common.exception.BusinessException("SYS013")));
    }

    @PostMapping
    public Result<SysMenu> create(@RequestBody SysMenu menu) {
        // 菜单直接决定管理端可见范围，属于提权面，要求超管
        permissionChecker.requireSuper();
        return Result.success(sysMenuService.create(menu));
    }

    @PutMapping("/{id}")
    public Result<SysMenu> update(@PathVariable Long id, @RequestBody SysMenu menu) {
        permissionChecker.requireSuper();
        return Result.success(sysMenuService.update(id, menu));
    }

    /**
     * 删除菜单（级联子孙，单事务）
     * <p>原实现写在 Controller 里，只删一层子菜单且两条 delete 不在同一事务：
     * 第二条失败会留下 parent_id 悬空的孤儿节点，且孙级菜单永远删不掉。</p>
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionChecker.requireSuper();
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