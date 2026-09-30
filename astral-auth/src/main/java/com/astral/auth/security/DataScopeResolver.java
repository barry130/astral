package com.astral.auth.security;

import cn.dev33.satoken.stp.StpUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结果级权限解析器：把「用户持有的范围权限」解析为「该用户在某个作用域下的<b>可见值集合</b>」。
 *
 * <h3>语义（关键）</h3>
 * <p>范围权限表达的是<b>可见性资格</b>，不是「强制返回该值」。例如轻听版本更新：</p>
 * <ul>
 *   <li>所有人可见 {@code stable}（基础值）；</li>
 *   <li>持有 {@code user:qt:update:channel:beta} 的人，可见集合 = {@code [stable, beta]}；</li>
 *   <li>业务侧在可见集合内按<b>业务规则</b>选取（版本更新取版本号最大者）。
 *       因此正式版版本号高于测试版时，持有测试权限的用户依然收到正式版。</li>
 * </ul>
 *
 * <p>这样「权限」与「决策」彻底解耦：权限框架只回答「你能看见哪些」，
 * 业务自己回答「看见之后选哪个」。新增渠道（rc/灰度等）只需登记一条权限码，
 * 框架与业务代码都不用改。</p>
 *
 * <h3>超管</h3>
 * <p>超管获得该作用域下<b>全部已登记</b>的范围值（自动包含未来新增的渠道），
 * 避免每次新增渠道都要回头给管理员补权限。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataScopeResolver {

    private final PermissionChecker permissionChecker;
    private final PermissionCatalog permissionCatalog;

    /**
     * 解析指定用户的可见值集合。
     *
     * @param loginId     登录用户 ID，为 null（未登录/无效 token）时只返回基础值
     * @param scopePrefix 作用域前缀，如 {@code user:qt:update:channel}
     * @param baseValues  所有用户都可见的基础值，如 {@code stable}
     * @return 可见值集合（保持插入序：基础值在前）
     */
    public Set<String> resolveVisibleValues(Object loginId, String scopePrefix, Collection<String> baseValues) {
        Set<String> visible = new LinkedHashSet<>();
        if (baseValues != null) {
            for (String base : baseValues) {
                if (base != null && !base.isBlank()) {
                    visible.add(base.trim().toLowerCase());
                }
            }
        }
        if (loginId == null || scopePrefix == null || scopePrefix.isBlank()) {
            return visible;
        }
        List<String> permissions = permissionChecker.permissionsOf(loginId);
        if (permissions.isEmpty()) {
            return visible;
        }
        String prefix = scopePrefix.endsWith(":") ? scopePrefix : scopePrefix + ":";
        boolean superUser = permissions.contains(PermissionChecker.SUPER_PERMISSION);
        if (superUser) {
            // 超管：全部已登记范围值
            addAllNormalized(visible, permissionCatalog.scopeValues(scopePrefix));
            return visible;
        }
        for (String code : permissions) {
            String value = PermissionCatalog.scopeValueOf(code, prefix);
            if (value == null) {
                continue;
            }
            if ("*".equals(value)) {
                // 显式通配：授予该作用域下全部范围值
                addAllNormalized(visible, permissionCatalog.scopeValues(scopePrefix));
            } else {
                visible.add(value.trim().toLowerCase());
            }
        }
        return visible;
    }

    private void addAllNormalized(Set<String> target, Set<String> values) {
        if (values == null) {
            return;
        }
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                target.add(v.trim().toLowerCase());
            }
        }
    }

    /**
     * 按请求携带的 token 解析可见值集合（免认证接口用；token 无效等同未登录）。
     *
     * @param token       会话 token（如请求头 satoken，可为 null）
     * @param scopePrefix 作用域前缀
     * @param baseValues  基础值
     * @return 可见值集合
     */
    public Set<String> resolveVisibleValuesByToken(String token, String scopePrefix, Collection<String> baseValues) {
        Object loginId = null;
        if (token != null && !token.isBlank()) {
            try {
                loginId = StpUtil.getLoginIdByToken(token);
            } catch (Exception e) {
                log.debug("token 解析失败，按未登录处理: {}", e.getMessage());
            }
        }
        return resolveVisibleValues(loginId, scopePrefix, baseValues);
    }

    /**
     * 轻听渠道可见集合（版本更新 / 音源包共用语义）。
     *
     * @param token 会话 token，可为 null
     * @param scope {@link PermissionChecker#QT_UPDATE_CHANNEL_SCOPE} 或
     *              {@link PermissionChecker#QT_SOURCE_CHANNEL_SCOPE}
     * @return 可见渠道集合，如 {@code [stable]} / {@code [stable, beta]}
     */
    public Set<String> resolveChannelsByToken(String token, String scope) {
        return resolveVisibleValuesByToken(token, scope, List.of(PermissionChecker.CHANNEL_STABLE));
    }

    /** 判断某渠道值是否对当前可见集合可见（渠道为空/脏数据时按基础值处理，大小写不敏感） */
    public static boolean visible(Set<String> visibleValues, String value, String fallbackValue) {
        if (visibleValues == null || visibleValues.isEmpty()) {
            return false;
        }
        String normalized = value == null || value.isBlank() ? fallbackValue : value.trim();
        if (normalized == null) {
            return false;
        }
        for (String v : visibleValues) {
            if (v.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }
}
