package com.astral.system.controller;

import com.astral.common.result.Result;
import com.astral.dao.entity.SysMenu;
import com.astral.dao.mapper.SysMenuMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin/system/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuMapper sysMenuMapper;

    @GetMapping("/tree")
    public Result<List<Map<String, Object>>> getMenuTree() {
        List<SysMenu> all = sysMenuMapper.selectList(new QueryWrapper<SysMenu>().orderByAsc("sort"));
        List<Map<String, Object>> tree = buildTree(all, 0L);
        return Result.success(tree);
    }

    @GetMapping("/list")
    public Result<List<Map<String, Object>>> getMenuList() {
        List<SysMenu> all = sysMenuMapper.selectList(new QueryWrapper<SysMenu>().orderByAsc("sort"));
        List<Map<String, Object>> result = all.stream().map(this::toMap).collect(Collectors.toList());
        return Result.success(result);
    }

    @GetMapping("/{id}")
    public Result<SysMenu> getById(@PathVariable Long id) {
        SysMenu menu = sysMenuMapper.selectById(id);
        return menu != null ? Result.success(menu) : Result.error("SYS009");
    }

    @PostMapping
    public Result<SysMenu> create(@RequestBody SysMenu menu) {
        menu.setId(null);
        sysMenuMapper.insert(menu);
        return Result.success(menu);
    }

    @PutMapping("/{id}")
    public Result<SysMenu> update(@PathVariable Long id, @RequestBody SysMenu menu) {
        SysMenu existing = sysMenuMapper.selectById(id);
        if (existing == null) return Result.error("SYS009");
        menu.setId(id);
        sysMenuMapper.updateById(menu);
        return Result.success(menu);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        sysMenuMapper.deleteById(id);
        sysMenuMapper.delete(new QueryWrapper<SysMenu>().eq("parent_id", id));
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