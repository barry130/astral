package com.astral.auth.security;

import com.astral.common.web.CsrfTokenSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;

/**
 * 管理端认证 Cookie 的读写器。
 *
 * <p>管理台（astral-front）此前把 Sa-Token 令牌存在 {@code localStorage}，任何前端 XSS
 * 都能直接读走令牌；改为 {@code HttpOnly} Cookie 后 JS 读不到，XSS 的收益被砍掉一大截。</p>
 *
 * <p><b>只给管理端用</b>：轻听 App（qt-uniappx / qt-pc）是原生/桌面客户端，没有 Cookie 容器，
 * 继续走 {@code satoken} 请求头。因此本类只在管理端（device=ADMIN）的登录链路上被调用。</p>
 *
 * <p>Cookie 属性：</p>
 * <ul>
 *   <li>{@code HttpOnly}：JS 不可读（唯一的目的是防 XSS 窃取）；</li>
 *   <li>{@code Secure}：仅 HTTPS 下发，受 {@code astral.auth.cookie-secure} 控制 ——
 *       本地 http 开发必须能关，否则浏览器根本不存；</li>
 *   <li>{@code SameSite}：默认 {@code Strict}，同源前端（Next.js rewrite 代理）不受影响，
 *       跨站发起的请求带不上凭据，是 CSRF 的第一道闸；</li>
 *   <li>{@code Path=/}：管理端前端与 API 都在同域下，不做路径收窄，避免漏带。</li>
 * </ul>
 */
@Component
public class AuthCookieWriter {

    /** Sa-Token 令牌名（{@code sa-token.token-name}）：Cookie 与请求头同名 */
    public static final String TOKEN_NAME = "satoken";

    /** 承载 Sa-Token 令牌的 Cookie 名（与 sa-token.token-name 同名，便于排查） */
    public static final String TOKEN_COOKIE = TOKEN_NAME;

    /** 显式携带令牌的请求头名：带它的调用方豁免 CSRF 双提交校验 */
    public static final String TOKEN_HEADER = TOKEN_NAME;

    /** CSRF Cookie 的兜底有效期（秒）：与登录缺省一致（3 天） */
    private static final long DEFAULT_CSRF_MAX_AGE = Duration.ofDays(3).toSeconds();

    private final boolean secure;
    private final String sameSite;

    public AuthCookieWriter(
            @Value("${astral.auth.cookie-secure:false}") boolean secure,
            @Value("${astral.auth.cookie-same-site:Strict}") String sameSite) {
        this.secure = secure;
        this.sameSite = sameSite;
    }

    /** 登录成功后写入认证 Cookie 与 CSRF Cookie；返回本次生成的 CSRF 令牌（回传给前端） */
    public String writeLoginCookies(HttpServletResponse response, String token, long timeoutSeconds) {
        long maxAge = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_CSRF_MAX_AGE;
        CsrfTokenSupport.writeCookie(response, TOKEN_COOKIE, token, true, secure, sameSite, "/", maxAge);
        String csrf = CsrfTokenSupport.newToken();
        // CSRF Cookie 必须让前端读得到（HttpOnly=false），否则回填不了请求头
        CsrfTokenSupport.writeCookie(response, CsrfTokenSupport.CSRF_COOKIE, csrf, false, secure, sameSite, "/", maxAge);
        return csrf;
    }

    /**
     * 读出当前请求的 CSRF 令牌；Cookie 不存在时<b>补发一枚</b>并返回。
     *
     * <p>为什么需要「补发」：令牌由登录响应写入 Cookie，但前端能否读到它取决于部署形态 ——
     * 前后端<b>跨域直连</b>（如 web.canace.cn 直连 astral.canace.cn）时，Cookie 的域是 API 域，
     * 前端页面所在域的 {@code document.cookie} <b>看不到它</b>，于是拿不到值、回填不了请求头，
     * 所有写请求都会被双提交校验拦成 403（AUTH013），而只读请求照常可用 —— 现象就是
     * 「能看不能改」。本方法让服务端把值随响应体交给前端（前端缓存在内存里），
     * 既不改双提交的安全模型（跨站页面依然读不到响应体），也不依赖任何域名配置。</p>
     *
     * <p>不重新生成已有令牌：并发在途的请求各自带着旧值发出的头，换值会让其中一部分撞上校验失败。</p>
     */
    public String ensureCsrfToken(HttpServletRequest request, HttpServletResponse response, long maxAgeSeconds) {
        String existing = CsrfTokenSupport.readCookie(request, CsrfTokenSupport.CSRF_COOKIE);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String csrf = CsrfTokenSupport.newToken();
        long maxAge = maxAgeSeconds > 0 ? maxAgeSeconds : DEFAULT_CSRF_MAX_AGE;
        CsrfTokenSupport.writeCookie(response, CsrfTokenSupport.CSRF_COOKIE, csrf, false, secure, sameSite, "/", maxAge);
        return csrf;
    }

    /**
     * 令牌续期时同步延长两个 Cookie。
     *
     * <p>认证 Cookie 不延会「会话还在、Cookie 先过期」，用户被莫名踢出；CSRF Cookie 不延则写请求
     * 会被 403 拦下（认证过了、指纹没了）。两者 max-age 必须同进同退。</p>
     *
     * <p>CSRF 令牌值原样沿用（不重新生成）：并发在途的请求各自带着旧值发出的头，
     * 换值会让其中一部分撞上校验失败；它的作用只是「跨站读不到」的指纹，不需要轮换。</p>
     */
    public void refreshCookies(HttpServletResponse response, String token, long timeoutSeconds) {
        if (token == null || token.isBlank() || timeoutSeconds <= 0) {
            return;
        }
        CsrfTokenSupport.writeCookie(response, TOKEN_COOKIE, token, true, secure, sameSite, "/", timeoutSeconds);

        HttpServletRequest request = currentRequest();
        String csrf = CsrfTokenSupport.readCookie(request, CsrfTokenSupport.CSRF_COOKIE);
        if (csrf != null && !csrf.isBlank()) {
            CsrfTokenSupport.writeCookie(response, CsrfTokenSupport.CSRF_COOKIE, csrf, false, secure, sameSite, "/", timeoutSeconds);
        }
    }

    /** 登出/失效：清掉认证与 CSRF Cookie */
    public void clearCookies(HttpServletResponse response) {
        CsrfTokenSupport.clearCookie(response, TOKEN_COOKIE, secure, sameSite, "/");
        CsrfTokenSupport.clearCookie(response, CsrfTokenSupport.CSRF_COOKIE, secure, sameSite, "/");
    }

    /**
     * 从当前请求上下文取响应。
     *
     * <p>控制器/service 层为了保持签名干净往往不注入 {@code HttpServletResponse}，
     * 而写 Cookie 只差这一个对象 —— 走 Spring 的 {@code RequestContextHolder} 取即可。
     * 上下文不可用（非 Web 线程、单元测试）时返回 null，调用方静默跳过。</p>
     */
    public static HttpServletResponse currentResponse() {
        ServletRequestAttributes attrs = currentAttributes();
        return attrs != null ? attrs.getResponse() : null;
    }

    /** 从当前请求上下文取请求（拿不到返回 null） */
    public static HttpServletRequest currentRequest() {
        ServletRequestAttributes attrs = currentAttributes();
        return attrs != null ? attrs.getRequest() : null;
    }

    private static ServletRequestAttributes currentAttributes() {
        try {
            return (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        } catch (Exception e) {
            return null;
        }
    }
}
