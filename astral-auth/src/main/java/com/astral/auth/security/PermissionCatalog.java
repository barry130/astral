package com.astral.auth.security;

import com.astral.dao.entity.Permission;
import com.astral.dao.mapper.PermissionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 权限目录：{@code sys_permission} 的全量只读视图（含权限码 → 权限、按作用域前缀取范围值）。
 *
 * <p>按全局权限版本（{@link PermissionCache#currentVersion()}）缓存，权限表发生任何写入后
 * 由注册器/管理端调用 {@code PermissionCache#bumpVersion()} 触发自动重载。</p>
 *
 * <p><b>作用域值</b>：对前缀 {@code user:qt:update:channel} 而言，库里登记为
 * {@code user:qt:update:channel:beta} 的权限提供一个范围值 {@code beta}。这是「结果级权限」
 * 数据驱动的关键：新增一个渠道只需登记一条权限，业务代码无需改动。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionCatalog {

    private final PermissionMapper permissionMapper;
    private final PermissionCache permissionCache;

    /** 快照：版本号 + 全量权限 + 权限码索引 */
    private volatile Snapshot snapshot;

    /** 全量权限（按 sort 升序） */
    public List<Permission> all() {
        return current().all();
    }

    /** 按权限码取权限定义 */
    public Optional<Permission> byCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(current().byCode().get(code));
    }

    /** 权限码是否存在 */
    public boolean exists(String code) {
        return byCode(code).isPresent();
    }

    /**
     * 取某作用域前缀下已登记的<b>全部范围值</b>（不含通配符本身）。
     *
     * @param scopePrefix 作用域前缀，如 {@code user:qt:update:channel}
     * @return 范围值集合，如 {@code [beta]}；无登记返回空集
     */
    public Set<String> scopeValues(String scopePrefix) {
        Set<String> values = new LinkedHashSet<>();
        if (scopePrefix == null || scopePrefix.isBlank()) {
            return values;
        }
        String prefix = scopePrefix.endsWith(":") ? scopePrefix : scopePrefix + ":";
        for (String code : current().byCode().keySet()) {
            String value = scopeValueOf(code, prefix);
            if (value != null && !"*".equals(value)) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * 从权限码中解析出相对作用域前缀的范围值。
     *
     * @param code   权限码，如 {@code user:qt:update:channel:beta}
     * @param prefix 已补冒号的前缀，如 {@code qt:update:channel:}
     * @return 范围值（可为 {@code *} 表示通配），不属于该前缀返回 null
     */
    static String scopeValueOf(String code, String prefix) {
        if (code == null || prefix == null || !code.startsWith(prefix)) {
            return null;
        }
        String value = code.substring(prefix.length());
        return value.isBlank() ? null : value;
    }

    /** 当前快照（版本变化时重载） */
    private Snapshot current() {
        String version = permissionCache.currentVersion();
        Snapshot local = snapshot;
        if (local != null && local.version().equals(version)) {
            return local;
        }
        synchronized (this) {
            local = snapshot;
            if (local != null && local.version().equals(version)) {
                return local;
            }
            List<Permission> all = permissionMapper.selectList(null);
            Map<String, Permission> byCode = new LinkedHashMap<>();
            for (Permission p : all) {
                if (p.getPermissionCode() != null) {
                    byCode.put(p.getPermissionCode(), p);
                }
            }
            Snapshot fresh = new Snapshot(version, all, byCode);
            snapshot = fresh;
            log.debug("权限目录已加载: {} 条", all.size());
            return fresh;
        }
    }

    /** 立即失效（注册器写库后调用，避免等待版本号传递） */
    public void invalidate() {
        snapshot = null;
    }

    private record Snapshot(String version, List<Permission> all, Map<String, Permission> byCode) {
    }
}
