package com.astral.auth.registry;

import com.astral.auth.security.PermissionCache;
import com.astral.auth.security.PermissionCatalog;
import com.astral.common.annotation.RequiresPermission;
import com.astral.dao.entity.Permission;
import com.astral.dao.mapper.PermissionMapper;
import com.astral.plugin.api.PermissionProvider;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限注册器：把「代码里声明的权限」登记进 {@code sys_permission}。
 *
 * <p>声明来源：控制器上的 {@code @RequiresPermission}（接口权限）与插件实现的
 * {@link PermissionProvider}（含结果级 DATA 权限）。启动时取并集，<b>只补齐缺失项</b>：
 * 已存在的权限码不动（后台改过的名称/排序/状态是运维数据，代码不覆盖）。</p>
 *
 * <p>这样权限树永远与实际代码一致，新增权限不再需要「改代码 + 手写 Flyway INSERT +
 * 后台点选」三处同步，也避免了漏登记导致的「权限树里没有、代码却在校验」。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionRegistry {

    private final ApplicationContext applicationContext;
    private final PermissionMapper permissionMapper;
    private final PermissionCatalog permissionCatalog;
    private final PermissionCache permissionCache;

    /** 是否在启动时自动登记缺失权限（默认开启；排障时可关闭） */
    @Value("${astral.permission.auto-register:true}")
    private boolean autoRegister;

    /**
     * 收集全部权限声明（同一权限码去重，先到先得）。
     *
     * @return 权限码 → 声明
     */
    public Map<String, PermissionDeclaration> collectDeclarations() {
        Map<String, PermissionDeclaration> declarations = new LinkedHashMap<>();
        collectFromControllers(declarations);
        collectFromPlugins(declarations);
        return declarations;
    }

    /**
     * 补齐缺失的权限登记。
     *
     * @return 实际新增的权限条数
     */
    public int registerMissing() {
        if (!autoRegister) {
            log.info("[PermissionRegistry] astral.permission.auto-register=false，跳过权限自动登记");
            return 0;
        }
        Map<String, PermissionDeclaration> declarations = collectDeclarations();
        if (declarations.isEmpty()) {
            return 0;
        }
        Set<String> existing = permissionCatalog.all().stream()
                .map(Permission::getPermissionCode)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());

        // 新权限排序从现有最大值往后排，保证管理端列表稳定
        int nextSort = permissionCatalog.all().stream()
                .map(Permission::getSort)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;

        List<PermissionDeclaration> missing = new ArrayList<>();
        for (PermissionDeclaration declaration : declarations.values()) {
            if (!existing.contains(declaration.getCode())) {
                missing.add(declaration);
            }
        }
        if (missing.isEmpty()) {
            log.info("[PermissionRegistry] 权限声明 {} 条，均已登记，无需补齐", declarations.size());
            return 0;
        }

        int inserted = 0;
        for (PermissionDeclaration declaration : missing) {
            try {
                Permission entity = new Permission();
                entity.setPermissionCode(declaration.getCode());
                entity.setPermissionName(declaration.resolvedName());
                entity.setDomain(declaration.resolvedDomain());
                entity.setType(declaration.getType());
                entity.setParentId(0L);
                entity.setSort(nextSort++);
                entity.setStatus(1);
                permissionMapper.insert(entity);
                inserted++;
                log.info("[PermissionRegistry] 新增权限登记: code={}, name={}, type={}, 来源={}",
                        declaration.getCode(), declaration.resolvedName(), declaration.getType(), declaration.getSource());
            } catch (Exception e) {
                log.warn("[PermissionRegistry] 权限登记失败（不影响启动）: code={}, err={}",
                        declaration.getCode(), e.getMessage());
            }
        }
        if (inserted > 0) {
            permissionCatalog.invalidate();
            permissionCache.bumpVersion();
            log.info("[PermissionRegistry] 本次共补齐 {} 条权限，权限目录缓存已失效", inserted);
        }
        return inserted;
    }

    // ==================== 声明收集 ====================

    /** 扫描控制器方法/类上的 @RequiresPermission */
    private void collectFromControllers(Map<String, PermissionDeclaration> declarations) {
        for (Class<? extends java.lang.annotation.Annotation> annotationType : List.of(RestController.class, Controller.class)) {
            for (Map.Entry<String, Object> entry : applicationContext.getBeansWithAnnotation(annotationType).entrySet()) {
                Object bean = entry.getValue();
                Class<?> targetClass = AopUtils.getTargetClass(bean);
                RequiresPermission typeLevel = AnnotationUtils.findAnnotation(targetClass, RequiresPermission.class);
                for (Method method : targetClass.getMethods()) {
                    RequiresPermission annotation = AnnotationUtils.findAnnotation(method, RequiresPermission.class);
                    if (annotation == null) {
                        annotation = typeLevel;
                    }
                    if (annotation == null) {
                        continue;
                    }
                    String source = "@RequiresPermission@" + targetClass.getSimpleName() + "#" + method.getName();
                    for (String code : annotation.value()) {
                        if (code == null || code.isBlank()) {
                            continue;
                        }
                        declarations.putIfAbsent(code.trim(),
                                PermissionDeclaration.fromAnnotation(annotation, source, code.trim()));
                    }
                }
            }
        }
    }

    /** 收集插件 SPI 声明的权限 */
    private void collectFromPlugins(Map<String, PermissionDeclaration> declarations) {
        for (PermissionProvider provider : applicationContext.getBeansOfType(PermissionProvider.class).values()) {
            String pluginId = provider.getClass().getSimpleName();
            List<PermissionProvider.PermissionDef> defs;
            try {
                defs = provider.getPermissions();
            } catch (Exception e) {
                log.warn("[PermissionRegistry] 插件权限声明读取失败: {}", pluginId, e);
                continue;
            }
            if (defs == null) {
                continue;
            }
            for (PermissionProvider.PermissionDef def : defs) {
                if (def == null || def.getCode() == null || def.getCode().isBlank()) {
                    continue;
                }
                declarations.putIfAbsent(def.getCode().trim(), PermissionDeclaration.fromPlugin(pluginId, def));
            }
        }
    }

    /** 当前库中已登记的权限码总数（诊断用） */
    public long countRegistered() {
        return permissionMapper.selectCount(new QueryWrapper<>());
    }
}
