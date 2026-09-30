package com.astral.system.controller;

import com.astral.auth.security.PermissionCache;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
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
import java.util.TreeMap;
import java.util.stream.Collectors;

@Tag(name = "权限管理")
@RestController
@RequestMapping("/api/v1/admin/system/permission")
@RequiredArgsConstructor
public class PermissionController {

    /** 查看权限（sys_permission: admin:system:permission:view），与前端 layout.tsx 的菜单权限一致 */
    private static final String PERM_VIEW = "admin:system:permission:view";

    private final PermissionService permissionService;
    private final RolePermissionMapper rolePermissionMapper;
    /** 权限缓存：权限定义变更后递增版本，立即失效所有用户的权限缓存 */
    private final PermissionCache permissionCache;

    @Operation(summary = "分页查询")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/page")
    public Result<Page<Permission>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<Permission> page = new Page<>(pageNum, pageSize);
        return Result.success(permissionService.page(page));
    }

    @Operation(summary = "根据ID查询")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/{id}")
    public Result<Permission> getById(@PathVariable Long id) {
        return Result.success(permissionService.getById(id));
    }

    @Operation(summary = "创建")
    // 权限决定接口可达性，属于提权面，要求超管
    @RequiresSuper
    @PostMapping
    public Result<Void> create(@Valid @RequestBody Permission entity) {
        permissionService.save(entity);
        // 权限定义变化会影响所有人的可见权限，递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresSuper
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody Permission entity) {
        entity.setId(id);
        // 防止 mass assignment：时间列不可由客户端改写
        entity.setCreateTime(null);
        entity.setUpdateTime(null);
        permissionService.updateById(entity);
        // 权限定义（含 status）变化会影响所有人的可见权限，递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresSuper
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        QueryWrapper<RolePermission> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("permission_id", id);
        long count = rolePermissionMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException("SYS006", count);
        }
        permissionService.removeById(id);
        // 权限定义变化会影响所有人的可见权限，递增权限版本
        permissionCache.bumpVersion();
        return Result.success();
    }

    /**
     * 权限树。
     *
     * <p>两种形态：</p>
     * <ul>
     *   <li>默认（不传 groupBy）：按 parentId 组织成菜单树，供菜单/路由类权限维护；</li>
     *   <li>{@code groupBy=domain}：按 {@code sys_permission.domain} 分组，供「角色-权限分配」使用——
     *       接口权限（type=4）与数据权限（type=5）不属于菜单树、parentId 为 0，
     *       若按 parentId 组装会散落成一堆顶级节点，无法阅读。分组节点的
     *       {@code id} 为空、只作展示层容器，前端据此不把分组本身当作权限提交。</li>
     * </ul>
     */
    @Operation(summary = "权限树（groupBy=domain 时按权限域分组）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/tree")
    public Result<List<Permission>> getTree(@RequestParam(required = false) String groupBy) {
        List<Permission> all = permissionService.list();
        if ("domain".equalsIgnoreCase(groupBy)) {
            return Result.success(buildDomainTree(all));
        }
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
     * 按权限域组装两层结构：域分组节点（id 为空）→ 该域下全部权限。
     *
     * <p>域内按「类型 → 排序 → 权限码」排序，保证同域权限稳定聚在一起且顺序可预期。
     * 历史脏数据（含 {@code *} 的通配行）不参与分组——超管身份已由 {@code sys_role.is_super} 表达。</p>
     */
    private List<Permission> buildDomainTree(List<Permission> all) {
        Map<String, List<Permission>> byDomain = new TreeMap<>();
        for (Permission p : all) {
            String code = p.getPermissionCode();
            if (code == null || code.contains("*")) {
                continue;
            }
            byDomain.computeIfAbsent(domainOf(p), k -> new ArrayList<>()).add(p);
        }
        List<Permission> result = new ArrayList<>(byDomain.size());
        for (Map.Entry<String, List<Permission>> entry : byDomain.entrySet()) {
            List<Permission> children = entry.getValue();
            children.sort(Comparator
                    .comparingInt((Permission p) -> p.getType() != null ? p.getType() : 0)
                    .thenComparingInt(p -> p.getSort() != null ? p.getSort() : 0)
                    .thenComparing(p -> p.getPermissionCode() != null ? p.getPermissionCode() : ""));
            Permission group = new Permission();
            // id 保持为 null：这是展示层容器，不是可授权的权限行
            group.setParentId(0L);
            group.setDomain(entry.getKey());
            group.setPermissionCode("domain:" + entry.getKey());
            group.setPermissionName(entry.getKey());
            group.setType(null);
            group.setSort(0);
            group.setStatus(1);
            group.setChildren(children);
            result.add(group);
        }
        return result;
    }

    /** 权限域：以 domain 字段为准，缺失（历史数据）时回退权限码首段 */
    private String domainOf(Permission permission) {
        String domain = permission.getDomain();
        if (domain != null && !domain.isBlank()) {
            return domain;
        }
        String code = permission.getPermissionCode();
        int idx = code == null ? -1 : code.indexOf(':');
        return idx > 0 ? code.substring(0, idx) : "system";
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
