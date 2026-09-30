package com.astral.auth.security;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.constant.PermissionType;
import com.astral.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限校验器（RBAC：用户 → 角色 → 权限，含 super 角色与层级通配）。
 *
 * <p>业务代码<b>优先使用声明式注解</b> {@code @RequiresPermission} / {@code @RequiresSuper}
 * （由 {@code PermissionAspect} 统一处理）；本类保留为编程式 API，供两类场景使用：</p>
 * <ul>
 *   <li>免认证接口按请求携带的 token 判定人群（如测试版投放，见 {@link DataScopeResolver}）；</li>
 *   <li>需要在方法内部按权限分支（而非拒绝请求）的场景，如「管理员豁免」。</li>
 * </ul>
 *
 * <p><b>编码规范</b>：{@code 端:域:资源:操作[:范围]}，全小写。</p>
 * <ul>
 *   <li><b>端</b>（首段）：{@code admin} 管理端 / {@code user} APP 端 / {@code all} 两端共用；</li>
 *   <li><b>域</b>（第 2 段）：对应 {@code sys_permission.domain}，如 {@code system} / {@code qt} / {@code storage}；
 *       见 {@link #domainOf(String)}——它会自动跳过「端」段；</li>
 *   <li><b>范围</b>（去掉「端」段后第 4 段起）用于结果级权限，如 {@code user:qt:update:channel:beta}。
 *       类型<b>由声明显式给出</b>（注解 {@code @RequiresPermission(type=...)} 默认 {@link PermissionType#API}，
 *       结果级权限声明 {@link PermissionType#DATA}；插件 {@code PermissionDef} 同理），
 *       <b>不要按段数推断</b>：{@code admin:system:mail:account:edit} 去掉端段后同样是 4 段，
 *       但它是正经的接口权限。</li>
 * </ul>
 *
 * <p><b>匹配规则</b></p>
 * <ol>
 *   <li>持有 {@link #SUPER_PERMISSION}（{@code *:*:*}）→ 通过一切校验；</li>
 *   <li>持有项支持层级通配：{@code admin:system:user:*} 可匹配 {@code admin:system:user:view}，
 *       也可用 {@code admin:*} 通配整个管理端；</li>
 *   <li>历史扁平权限码（{@code qt_admin} / {@code qt_tester}）通过 {@link #LEGACY_ALIASES}
 *       映射到新码，过渡期结束后删除别名表。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PermissionChecker {

    /** 超级管理员通配权限（由 sys_role.is_super 角色在权限列表中合成，不再落库） */
    public static final String SUPER_PERMISSION = "*:*:*";

    /** 权限码首段（端）：管理端 */
    public static final String SIDE_ADMIN = "admin";

    /** 权限码首段（端）：APP 端 */
    public static final String SIDE_USER = "user";

    /** 权限码首段（端）：管理端与 APP 端共用 */
    public static final String SIDE_ALL = "all";

    /** 轻听管理权限（管理端 /api/v1/admin/qt/** 与音源包后台） */
    public static final String QT_ADMIN_PERMISSION = "admin:qt:admin";

    /** 轻听版本更新渠道作用域前缀（可见值集合：stable 为基础值，beta 需授权） */
    public static final String QT_UPDATE_CHANNEL_SCOPE = "user:qt:update:channel";

    /** 轻听音源包渠道作用域前缀 */
    public static final String QT_SOURCE_CHANNEL_SCOPE = "user:qt:source:channel";

    /** 正式渠道：所有用户可见的基础值 */
    public static final String CHANNEL_STABLE = "stable";

    /** 测试渠道：需要 {scope}:beta 权限才可见 */
    public static final String CHANNEL_BETA = "beta";

    /**
     * 历史权限码别名（旧扁平码 → 新分层码）。
     *
     * <p>数据迁移（V20260930001）已把库里的旧码搬迁为右侧新码，别名表用于兜底：
     * 未同步迁移的外部配置、旧客户端/文档仍按旧码判断时不会失效。
     * <b>过渡期结束后（所有调用方切换完成）应删除本表</b>。</p>
     */
    public static final Map<String, List<String>> LEGACY_ALIASES = Map.of(
            "qt_admin", List.of(QT_ADMIN_PERMISSION),
            "qt_tester", List.of(
                    QT_UPDATE_CHANNEL_SCOPE + ":" + CHANNEL_BETA,
                    QT_SOURCE_CHANNEL_SCOPE + ":" + CHANNEL_BETA)
    );

    /** Sa-Token 权限数据源（StpInterfaceImpl），用于按 loginId 显式拉取权限列表 */
    private final StpInterface stpInterface;

    /** 权限不足错误码（error-codes.properties COMMON005） */
    private static final String ERROR_CODE_NO_PERMISSION = "COMMON005";

    // ==================== 断言式校验（拒绝请求） ====================

    /**
     * 校验当前登录用户是否具备指定权限，无权限时抛出业务异常。
     *
     * @param permissionCode 权限编码，如 admin:system:mail:view
     */
    public void require(String permissionCode) {
        if (!StpUtil.isLogin()) {
            // 未登录由 AuthInterceptor 拦截，此处不重复处理
            return;
        }
        if (hasPermission(permissionCode)) {
            return;
        }
        log.warn("权限校验失败: userId={}, permission={}", StpUtil.getLoginIdDefaultNull(), permissionCode);
        throw new BusinessException(ERROR_CODE_NO_PERMISSION);
    }

    /** 校验多个权限（任一命中即通过），无权限时抛出业务异常 */
    public void requireAny(String... permissionCodes) {
        if (!StpUtil.isLogin()) {
            return;
        }
        if (hasAnyPermission(permissionCodes)) {
            return;
        }
        log.warn("权限校验失败(any): userId={}, permissions={}",
                StpUtil.getLoginIdDefaultNull(), String.join(",", permissionCodes));
        throw new BusinessException(ERROR_CODE_NO_PERMISSION);
    }

    /**
     * 校验当前登录用户是否为超级管理员（持有 {@code *:*:*}）。
     *
     * <p>用于<b>提权类</b>接口：重置他人密码、修改用户角色、修改角色权限。
     * 这类操作一旦被非超管执行，攻击者可以给自己加上任意权限，
     * 因此要求最严格的权限，而不是普通的 {@code :view} 权限。</p>
     */
    public void requireSuper() {
        if (!StpUtil.isLogin()) {
            return;
        }
        if (hasPermission(SUPER_PERMISSION) || isSuperUser()) {
            return;
        }
        log.warn("超级管理员权限校验失败: userId={}", StpUtil.getLoginIdDefaultNull());
        throw new BusinessException(ERROR_CODE_NO_PERMISSION);
    }

    // ==================== 判定式校验 ====================

    /** 当前用户是否具备指定权限（含超管通配、层级通配、历史别名） */
    public boolean hasPermission(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return false;
        }
        List<String> permissions = StpUtil.getPermissionList();
        return matchesAny(permissions, permissionCode);
    }

    /** 当前用户是否具备任一权限 */
    public boolean hasAnyPermission(String... permissionCodes) {
        if (permissionCodes == null || permissionCodes.length == 0) {
            return false;
        }
        List<String> permissions = StpUtil.getPermissionList();
        for (String code : permissionCodes) {
            if (matchesAny(permissions, code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断指定登录用户是否具备任一权限编码（含超管通配）。
     * <p>供免认证接口按请求携带的 token 显式判定人群（如测试版投放），
     * 不依赖当前线程的登录上下文；loginId 为 null 返回 false。</p>
     *
     * @param loginId         登录用户 ID（可为 null）
     * @param permissionCodes 权限编码列表，命中任一即返回 true
     * @return 具备任一权限返回 true
     */
    public boolean hasAnyPermissionByLoginId(Object loginId, String... permissionCodes) {
        if (loginId == null) {
            return false;
        }
        List<String> permissions = permissionsOf(loginId);
        for (String code : permissionCodes) {
            if (matchesAny(permissions, code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按会话 token 判断对应登录用户是否具备任一权限编码（含超管通配）。
     * <p>token 为空或无效（无法反查登录用户）返回 false，即视为非测试人群。</p>
     *
     * @param token           会话 token（如请求头 satoken，可为 null）
     * @param permissionCodes 权限编码列表，命中任一即返回 true
     * @return 具备任一权限返回 true
     */
    public boolean hasAnyPermissionByToken(String token, String... permissionCodes) {
        Object loginId = loginIdByToken(token);
        return hasAnyPermissionByLoginId(loginId, permissionCodes);
    }

    /**
     * 指定用户的权限码列表（走缓存，含超管合成）。
     *
     * @param loginId 登录用户 ID，为 null 返回空列表
     */
    public List<String> permissionsOf(Object loginId) {
        if (loginId == null) {
            return List.of();
        }
        List<String> permissions = stpInterface.getPermissionList(loginId, StpUtil.TYPE);
        return permissions == null ? List.of() : permissions;
    }

    /** 当前登录用户的权限码列表 */
    public List<String> currentPermissions() {
        List<String> permissions = StpUtil.getPermissionList();
        return permissions == null ? List.of() : permissions;
    }

    /** 指定用户是否为超级管理员（权限列表含通配符） */
    public boolean isSuperUser(Object loginId) {
        return permissionsOf(loginId).contains(SUPER_PERMISSION);
    }

    /** 当前登录用户是否为超级管理员 */
    public boolean isSuperUser() {
        return currentPermissions().contains(SUPER_PERMISSION);
    }

    /** 由 token 反查登录用户ID（无效返回 null） */
    public Object loginIdByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return StpUtil.getLoginIdByToken(token);
        } catch (Exception e) {
            log.debug("satoken 解析失败: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 匹配算法 ====================

    /**
     * 判断权限列表是否命中某个（可能带通配/别名的）权限码。
     *
     * @param held       用户持有的权限码列表（可能含 {@code *:*:*}）
     * @param required   要求的权限码
     */
    public static boolean matchesAny(List<String> held, String required) {
        if (required == null || required.isBlank()) {
            return false;
        }
        if (held == null || held.isEmpty()) {
            return false;
        }
        if (held.contains(SUPER_PERMISSION)) {
            return true;
        }
        Set<String> requiredForms = expandAliases(required);
        for (String h : held) {
            for (String heldForm : expandAliases(h)) {
                for (String want : requiredForms) {
                    if (matches(heldForm, want)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 单个权限码匹配：支持 {@code 段:*} 层级通配。
     *
     * <pre>
     * system:user:*  ←→  admin:system:user:view   （命中）
     * system:*       ←→  admin:system:user:view   （命中）
     * system:user:*  ←→  qt:update:view     （不命中）
     * </pre>
     */
    public static boolean matches(String held, String required) {
        if (held == null || required == null) {
            return false;
        }
        if (held.equals(required) || SUPER_PERMISSION.equals(held)) {
            return true;
        }
        if (held.endsWith(":*")) {
            // 去掉通配段保留分隔符：system:user:* → system:user:
            String prefix = held.substring(0, held.length() - 1);
            return required.startsWith(prefix);
        }
        return false;
    }

    /** 展开历史别名（返回自身 + 别名目标） */
    public static Set<String> expandAliases(String code) {
        Set<String> forms = new LinkedHashSet<>();
        if (code == null || code.isBlank()) {
            return forms;
        }
        forms.add(code);
        List<String> targets = LEGACY_ALIASES.get(code);
        if (targets != null) {
            forms.addAll(targets);
        }
        return forms;
    }

    /**
     * 去掉权限码首段的「端」，返回 {@code 域:资源:操作[:范围]}。
     *
     * <p>首段不是 {@code admin} / {@code user} / {@code all} 时原样返回——
     * 这样未带端前缀的历史码（如 {@code user:qt:update:channel:beta}）仍能被正确解析。</p>
     */
    public static String stripSide(String code) {
        if (code == null || code.isBlank()) {
            return code;
        }
        int idx = code.indexOf(':');
        if (idx <= 0) {
            return code;
        }
        return isSide(code.substring(0, idx)) ? code.substring(idx + 1) : code;
    }

    /**
     * 权限码首段的「端」。
     *
     * @return {@code admin} / {@code user} / {@code all}；未带端前缀返回 {@code null}
     */
    public static String sideOf(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        int idx = code.indexOf(':');
        if (idx <= 0) {
            return null;
        }
        String first = code.substring(0, idx);
        return isSide(first) ? first : null;
    }

    private static boolean isSide(String segment) {
        return SIDE_ADMIN.equals(segment) || SIDE_USER.equals(segment) || SIDE_ALL.equals(segment);
    }

    /**
     * 权限码 → 权限域（**跳过首段「端」**）。
     *
     * <p>编码规范为 {@code 端:域:资源:操作[:范围]}，域取第 2 段；
     * 未带端前缀的历史码取首段。</p>
     *
     * @param code 权限码，如 {@code admin:system:user:view}
     * @return 域，取不到时返回 {@code system}
     */
    public static String domainOf(String code) {
        String rest = stripSide(code);
        if (rest == null || rest.isBlank()) {
            return "system";
        }
        int idx = rest.indexOf(':');
        return idx > 0 ? rest.substring(0, idx) : "system";
    }
}
