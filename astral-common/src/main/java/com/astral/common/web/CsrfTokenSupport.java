package com.astral.common.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 管理端 CSRF 双提交令牌（double-submit cookie）。
 *
 * <p>管理台把认证令牌放进 {@code HttpOnly} Cookie 之后，浏览器会自动携带它，
 * 于是「跨站发起的写请求」也自动带着凭据 —— 这正是 CSRF 成立的前提。
 * 纯 Header 承载 token 时不存在该风险（攻击者无法读取 token），改用 Cookie 必须补上防护。</p>
 *
 * <p>方案沿用业界最省事的双提交：</p>
 * <ol>
 *   <li>登录成功时下发一枚<b>随机</b> {@code astral_csrf} Cookie，<b>不带 HttpOnly</b>（JS 要读它回填请求头）；</li>
 *   <li>前端把该值放进 {@code X-CSRF-Token} 请求头；</li>
 *   <li>服务端在 Cookie 承载认证的请求上，校验「Cookie 值 == 请求头值」，不匹配一律 403。</li>
 * </ol>
 *
 * <p>跨站页面读不到 Cookie 值（同源策略），因此伪造不出匹配的头，写请求会被拦下。
 * 令牌本身是随机串、与会话凭据解耦，不做服务端存储。</p>
 *
 * <p><b>令牌为什么还要随响应体下发一份</b>：Cookie 能否被前端读到取决于部署形态。
 * 同源部署（Next.js rewrites 代理）下读得到；<b>跨域直连</b>部署（如 {@code web.canace.cn}
 * 直连 {@code astral.canace.cn}）下本 Cookie 属于 API 域，页面所在域的 {@code document.cookie}
 * 看不到它 —— 前端拿不到值就回填不了请求头，所有写请求被 403（AUTH013）拦下，而只读请求
 * 正常，表现为「能看不能改」。因此登录与 {@code /api/v1/all/auth/info} 的响应体里
 * 也带上该值（见 {@code LoginResponse.csrfToken}）。这不削弱双提交模型：跨站页面同样
 * 读不到我们的响应体。</p>
 *
 * <p><b>为什么只对管理端区生效</b>：轻听 App 客户端（qt-uniappx / qt-pc）与其它第三方调用方
 * 仍然走 {@code satoken} 请求头（Header 是显式携带，不存在 CSRF），强制它们参与双提交
 * 会直接打断已发布的客户端。</p>
 */
public final class CsrfTokenSupport {

    /** CSRF Cookie 名（非 HttpOnly，前端读取后回填请求头） */
    public static final String CSRF_COOKIE = "astral_csrf";

    /** CSRF 请求头名 */
    public static final String CSRF_HEADER = "X-CSRF-Token";

    /** 不参与 CSRF 校验的方法：只读语义，不产生副作用 */
    private static final java.util.Set<String> SAFE_METHODS =
            java.util.Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** 128 位随机令牌：足够抗猜测，又不必上 HMAC */
    private static final SecureRandom RANDOM = new SecureRandom();

    private CsrfTokenSupport() {
    }

    /** 请求方法是否为只读（不需要 CSRF 校验） */
    public static boolean isSafeMethod(String method) {
        return method != null && SAFE_METHODS.contains(method.toUpperCase());
    }

    /** 读取 Cookie 值（request 为 null 或 Cookie 不存在时返回 null） */
    public static String readCookie(HttpServletRequest request, String name) {
        if (request == null) {
            return null;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** 写 Cookie（HttpOnly / Secure / SameSite / Path / Max-Age 由调用方决定） */
    public static void writeCookie(HttpServletResponse response, String name, String value,
                                   boolean httpOnly, boolean secure, String sameSite,
                                   String path, long maxAgeSeconds) {
        StringBuilder header = new StringBuilder()
                .append(name).append('=').append(value)
                .append("; Path=").append(path)
                .append("; Max-Age=").append(maxAgeSeconds);
        if (httpOnly) {
            header.append("; HttpOnly");
        }
        if (secure) {
            header.append("; Secure");
        }
        if (sameSite != null && !sameSite.isBlank()) {
            header.append("; SameSite=").append(sameSite);
        }
        response.addHeader("Set-Cookie", header.toString());
    }

    /** 清除 Cookie（Max-Age=0，属性与下发时保持一致，否则浏览器可能拒绝覆盖） */
    public static void clearCookie(HttpServletResponse response, String name,
                                   boolean secure, String sameSite, String path) {
        writeCookie(response, name, "", true, secure, sameSite, path, 0);
    }

    /** 生成一枚新的 CSRF 令牌（URL 安全 Base64，无填充） */
    public static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 常量时间比较，避免用响应时间侧信道逐字节猜令牌。
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
