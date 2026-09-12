package com.astral.server.interceptor;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.result.Result;
import com.astral.qt.common.QtRestResp;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** 表结构管理免认证前缀（历史约定保留） */
    private static final String TABLE_SCHEMA_PREFIX = "/api/v1/system/table-schema";
    private static final String TABLE_SCHEMA_ADMIN_PREFIX = "/api/v1/admin/system/table-schema";

    /** 免认证路径白名单（合并原宿主 WebMvcConfig excludes 与 qt 插件 PUBLIC_PATHS） */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            // 宿主认证（旧 /api/v1/auth 与新 /api/v1/all/auth）
            "/api/v1/auth/public-key", "/api/v1/auth/login", "/api/v1/auth/logout",
            "/api/v1/all/auth/public-key", "/api/v1/all/auth/login", "/api/v1/all/auth/logout",
            // 轻听用户端免认证（旧 /api/v1/user 与新 /api/v1/app/user）
            "/api/v1/user/login",
            "/api/v1/user/register",
            "/api/v1/user/email",
            "/api/v1/user/changePass",
            "/api/v1/user/upload",
            "/api/v1/user/refresh",
            "/api/v1/app/user/login",
            "/api/v1/app/user/register",
            "/api/v1/app/user/email",
            "/api/v1/app/user/changePass",
            "/api/v1/app/user/upload",
            "/api/v1/app/user/refresh",
            // 全端统计：匿名上报入口放行（STATS_DESIGN.md D1）
            "/api/v1/stat/report",
            "/api/v1/app/stat/report"
    );

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String uri = request.getRequestURI();
        String token = request.getHeader(TOKEN_HEADER);

        // 1. Token 自动续期（所有区域通用；匿名接口带了有效 token 也续）
        renewIfNeeded(token);

        // 2. 免认证白名单直接放行
        if (PUBLIC_PATHS.contains(uri)
                || uri.startsWith(TABLE_SCHEMA_PREFIX)
                || uri.startsWith(TABLE_SCHEMA_ADMIN_PREFIX)) {
            return true;
        }

        // 3. App 匿名公共区：/api/v1/app/** 中非 user 区（版本更新等）免认证，携带有效 token 时仅做续期
        //    （必须排除 /api/v1/app/user/** 子树，该区域仍需登录校验）
        if (uri.startsWith(APP_PUBLIC_PREFIX) && !uri.startsWith(QT_USER_PREFIX_NEW)) {
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

        // 从 Sa-Token 会话中读取用户信息（登录时已存入），避免每次请求查库
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            request.setAttribute("userId", loginId.toString());
            setUsernameAttribute(request);
            log.debug("请求用户: userId={}", loginId);
        }
        return true;
    }

    /** Token 滑动续期：剩余有效期不足阈值时续满（任何异常不影响请求） */
    private void renewIfNeeded(String token) {
        if (renewThreshold <= 0 || token == null || token.isBlank()) {
            return;
        }
        try {
            // token 无效/已过期时 getLoginIdByToken 返回 null，直接跳过
            if (StpUtil.getLoginIdByToken(token) == null) {
                return;
            }
            long remain = StpUtil.getTokenTimeout(token);
            if (remain > 0 && remain < renewThreshold) {
                StpUtil.renewTimeout(token, tokenTimeout);
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
