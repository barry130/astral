package com.astral.server.interceptor;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.security.LoginUserTypeResolver;
import com.astral.auth.service.UserLoginMarker;
import com.astral.common.result.Result;
import com.astral.qt.common.QtRestResp;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 全局统一认证拦截器（宿主管理端 + 轻听插件用户端合并）
 * <p>基于 Sa-Token 实现请求认证拦截：</p>
 * <ul>
 *   <li><b>宿主管理区</b>（/api/v1/** 除 Qt 用户区外）：未登录返回 {@code Result} 结构 401（AUTH001），
 *       登录后把 userId/username 写入 request 属性（供限流拦截器与控制器使用）</li>
 *   <li><b>Qt 用户区</b>（/api/v1/user/**、/api/v1/app/user/**）：未携带/无效 satoken 返回
 *       {@code QtRestResp} 结构 401（“登录状态已失效”，与历史 App 客户端约定一致），
 *       登录后把 userId/username 写入 request 属性</li>
 *   <li><b>Token 自动续期</b>：任何 /api/** 请求只要携带有效 satoken（含匿名接口如 /api/v1/app/update），
 *       剩余有效期低于阈值（astral.auth.token-renew-threshold，默认 1 天）时自动续满
 *       sa-token.timeout（默认 3 天），实现活跃用户滑动续期；
 *       每请求仅一次 Redis 读，临近过期才触发一次写</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    /** JSON序列化器，用于将错误响应写入响应体 */
    private final ObjectMapper objectMapper;

    /** 登录用户类型解析器：区分「管理端用户」与「App 用户」 */
    private final LoginUserTypeResolver loginUserTypeResolver;

    /** 登录/活跃标记：滑动续期发生时回写 sys_user.login_time/login_ip */
    private final UserLoginMarker userLoginMarker;

    /**
     * 管理端接口要求的 user_type，默认 {@code ADMIN}。
     * 配成空串可关闭管理端身份门禁（仅应急排障用）。
     */
    @Value("${astral.auth.admin-required-user-type:ADMIN}")
    private String adminRequiredUserType;

    /** 续期阈值（秒）：剩余有效期低于该值时自动续满（0=关闭自动续期） */
    @Value("${astral.auth.token-renew-threshold:86400}")
    private long renewThreshold;

    /** 续期目标时长（秒）：默认取 sa-token.timeout（259200 = 3 天） */
    @Value("${sa-token.timeout:259200}")
    private long tokenTimeout;

    /** satoken 请求头名（与 sa-token.token-name 一致） */
    private static final String TOKEN_HEADER = "satoken";

    /** Qt 用户区前缀：旧 /api/v1/user 与新 /api/v1/app/user，401 返回 QtRestResp 结构 */
    private static final String QT_USER_PREFIX_OLD = "/api/v1/user/";
    private static final String QT_USER_PREFIX_NEW = "/api/v1/app/user/";

    /** App 匿名公共区前缀：/api/v1/app/** 中非 user 区（如 /api/v1/app/update、/api/v1/app/stat/report）免认证 */
    private static final String APP_PUBLIC_PREFIX = "/api/v1/app/";

    /**
     * 宿主管理端前缀。
     *
     * <p>该前缀下的接口需要「已登录 + 管理端身份（{@code sys_user.user_type = ADMIN}）」双重校验。
     * 原因：管理端与轻听 App 端共用同一套 Sa-Token 命名空间，App 注册接口又是免认证的，
     * 只校验 {@code StpUtil.isLogin()} 会让任何注册用户都能调用管理端接口。</p>
     */
    private static final String ADMIN_PREFIX = "/api/v1/admin/";

    /**
     * storage 插件 Worker 专属路径：仅接受 HMAC 服务身份（StorageWorkerAuthInterceptor），
     * 不接受浏览器 satoken。必须精确限定到 worker/origin 两个子树，
     * 不能放行整个 /api/v1/all/storage/**（用户接口仍需登录）。
     */
    private static final String STORAGE_WORKER_PREFIX = "/api/v1/all/storage/worker/";
    private static final String STORAGE_ORIGIN_PREFIX = "/api/v1/all/storage/origin/";

    /** 免认证路径白名单（合并原宿主 WebMvcConfig excludes 与 qt 插件 PUBLIC_PATHS） */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            // 宿主认证（旧 /api/v1/auth 与新 /api/v1/all/auth）
            "/api/v1/auth/public-key", "/api/v1/auth/login", "/api/v1/auth/logout",
            "/api/v1/all/auth/public-key", "/api/v1/all/auth/login", "/api/v1/all/auth/logout",
            // 轻听用户端免认证（旧 /api/v1/user 与新 /api/v1/app/user）
            // 注意：upload 已从白名单移除 —— 匿名上传 + 同源静态目录 = 存储型 XSS，
            // 头像上传属于「登录后才该做的事」，客户端请先登录再上传。
            "/api/v1/user/login",
            "/api/v1/user/register",
            "/api/v1/user/email",
            "/api/v1/user/changePass",
            "/api/v1/user/refresh",
            "/api/v1/app/user/login",
            "/api/v1/app/user/register",
            "/api/v1/app/user/email",
            "/api/v1/app/user/changePass",
            "/api/v1/app/user/refresh",
            // 全端统计：匿名上报入口放行
            "/api/v1/stat/report",
            "/api/v1/app/stat/report"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        String token = request.getHeader(TOKEN_HEADER);

        // 1. Token 自动续期（所有区域通用；匿名接口带了有效 token 也续）
        renewIfNeeded(request, token);

        // 2. 免认证白名单直接放行
        //    注意：这里只做「精确路径」匹配，不再有 startsWith 前缀放行。
        //    宽前缀放行（曾经的 /api/v1/system/table-schema、/api/v1/admin/system/table-schema）
        //    会让该前缀下「未来新增的接口」默认匿名，保护责任被下推给后续开发者，已移除。
        if (PUBLIC_PATHS.contains(uri)) {
            return true;
        }

        // 3. App 匿名公共区：/api/v1/app/** 中非 user 区（版本更新等）免认证，携带有效 token 时仅做续期
        //    （必须排除 /api/v1/app/user/** 子树，该区域仍需登录校验）
        if (uri.startsWith(APP_PUBLIC_PREFIX) && !uri.startsWith(QT_USER_PREFIX_NEW)) {
            return true;
        }

        // 3.5 storage 插件 Worker 专属路径：由 StorageWorkerAuthInterceptor 做 HMAC 认证（STORAGE017），
        //     跳过 satoken 校验；HMAC 拦截器由 storage 的 Web 配置按精确前缀注册
        if (uri.startsWith(STORAGE_WORKER_PREFIX) || uri.startsWith(STORAGE_ORIGIN_PREFIX)) {
            return true;
        }

        // 4. Qt 用户区：校验 satoken 有效性，401 返回 QtRestResp 结构（App 客户端历史约定）
        if (uri.startsWith(QT_USER_PREFIX_OLD) || uri.startsWith(QT_USER_PREFIX_NEW)) {
            Long userId = resolveUserId(token);
            if (userId == null) {
                response.setContentType("application/json;charset=UTF-8");
                response.setStatus(401);
                response.getWriter().write(objectMapper.writeValueAsString(
                        QtRestResp.error(401, "登录状态已失效")
                ));
                return false;
            }
            request.setAttribute("userId", userId);
            setUsernameAttribute(request);
            return true;
        }

        // 4. 宿主管理区：校验登录态，401 返回 Result 结构（AUTH001）
        if (!StpUtil.isLogin()) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            Result<?> result = Result.error("AUTH001");
            result.setCode(401);
            response.getWriter().write(objectMapper.writeValueAsString(result));
            return false;
        }

        // 5. 管理端身份门禁：/api/v1/admin/** 必须是管理端用户（user_type=ADMIN）
        //    管理端与 App 端共用 Sa-Token 命名空间，App 注册免认证 —— 只判 isLogin()
        //    等于把管理端对所有注册用户开放。此处按 sys_user.user_type 区分身份。
        if (uri.startsWith(ADMIN_PREFIX) && !passAdminIdentityGate(response)) {
            return false;
        }

        // 从 Sa-Token 会话中读取用户信息（登录时已存入），避免每次请求查库
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            request.setAttribute("userId", loginId.toString());
            setUsernameAttribute(request);
            log.debug("请求用户: userId={}", loginId);
        }
        return true;
    }

    /**
     * 管理端身份门禁。
     *
     * <p>要求当前登录用户的 {@code sys_user.user_type} 等于 {@link #adminRequiredUserType}
     * （默认 {@code ADMIN}）。该列在 V20260914001 中定义为
     * {@code NOT NULL DEFAULT 'ADMIN'}，历史管理员天然满足，不会因引入校验被锁在门外。</p>
     *
     * <p>把 {@code astral.auth.admin-required-user-type} 配成空串可关闭本校验
     * （仅用于排障应急，不要在生产长期关闭）。</p>
     *
     * @return 通过返回 true；否则已写入 403 响应并返回 false
     */
    private boolean passAdminIdentityGate(HttpServletResponse response) throws Exception {
        if (adminRequiredUserType == null || adminRequiredUserType.isBlank()) {
            return true;
        }
        Object loginId = StpUtil.getLoginIdDefaultNull();
        String userType = loginUserTypeResolver.resolve(loginId);
        if (adminRequiredUserType.equals(userType)) {
            return true;
        }

        log.warn("管理端身份门禁拒绝: userId={}, userType={}, 要求={}",
                loginId, userType, adminRequiredUserType);

        response.setContentType("application/json;charset=UTF-8");
        response.setStatus(403);
        Result<?> result = Result.error("COMMON005");
        result.setCode(403);
        response.getWriter().write(objectMapper.writeValueAsString(result));
        return false;
    }

    /**
     * Token 滑动续期：剩余有效期不足阈值时续满（任何异常不影响请求）。
     *
     * <p>续期发生 = 用户仍活跃，同步回写 {@code sys_user.login_time/login_ip}，
     * 使「最后活跃」口径覆盖未重新登录但持续使用的用户（回写失败不影响请求）。</p>
     */
    private void renewIfNeeded(HttpServletRequest request, String token) {
        if (renewThreshold <= 0 || token == null || token.isBlank()) {
            return;
        }
        try {
            // token 无效/已过期时 getLoginIdByToken 返回 null，直接跳过
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId == null) {
                return;
            }
            long remain = StpUtil.getTokenTimeout(token);
            if (remain > 0 && remain < renewThreshold) {
                StpUtil.renewTimeout(token, tokenTimeout);
                userLoginMarker.mark(Long.parseLong(loginId.toString()), request);
                log.debug("token 已自动续期: remain={}s -> {}s", remain, tokenTimeout);
            }
        } catch (Exception e) {
            log.debug("token 续期跳过: {}", e.getMessage());
        }
    }

    /** 从 satoken 请求头解析登录用户ID（无效/未携带返回 null） */
    private Long resolveUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Object loginId = StpUtil.getLoginIdByToken(token);
            if (loginId != null) {
                // loginId 可能是 String 或 Number，统一按字符串解析为 Long
                return Long.parseLong(loginId.toString());
            }
        } catch (Exception e) {
            log.debug("satoken 解析失败: {}", e.getMessage());
        }
        return null;
    }

    /** 从 Sa-Token 会话读取用户名写入 request 属性（无会话时回退 userId） */
    private void setUsernameAttribute(HttpServletRequest request) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        String username = null;
        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            username = (String) session.get("username");
        }
        if (username == null) {
            username = loginId != null ? loginId.toString() : null;
        }
        if (username != null) {
            request.setAttribute("username", username);
        }
    }
}
