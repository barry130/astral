package com.astral.auth.dto;

import lombok.Data;

import java.util.List;

/**
 * 登录响应DTO
 * <p>
 * 封装登录成功后返回给客户端的用户信息和认证凭据。
 * </p>
 */
@Data
public class LoginResponse {
    /** 用户ID */
    private Long id;
    /** 用户名 */
    private String username;
    /** 用户昵称 */
    private String nickname;
    /** Sa-Token 认证令牌，后续请求需携带此令牌 */
    private String token;
    /** 用户角色编码列表，用于前端权限控制 */
    private List<String> roles;
    /** 用户权限编码列表，用于前端按钮级权限控制 */
    private List<String> permissions;
    /** 用户类型：ADMIN 管理端 / APP 轻听 App 端（前端据此区分入口，后端据此做管理端身份门禁） */
    private String userType;
    /** 是否需要强制改密：1=是（管理端登录后必须先修改密码）；0/缺省=否 */
    private Integer mustChangePassword;
    /**
     * CSRF 令牌（双提交用）。
     *
     * <p>凡是走 Cookie 凭据的登录用户都会返回：令牌已由服务端同时写入非 HttpOnly 的
     * {@code astral_csrf} Cookie，前端把它原样回填到 {@code X-CSRF-Token} 请求头即可。
     * 不按 user_type 过滤 —— 网页端的普通用户（APP）同样能用 Cookie 登录并发出写请求
     * （如 {@code /imgbed} 的删除、改可见性），而后端的双提交校验只看「凭据是否来自 Cookie」。
     * 轻听 App 走 {@code satoken} 请求头认证，被豁免该校验，取到值也不使用。</p>
     *
     * <p>为什么 Cookie 之外还要在响应体里给一份：<b>跨域直连</b>部署（如 web.canace.cn
     * 直连 astral.canace.cn）时，该 Cookie 属于 API 域，页面所在域的 {@code document.cookie}
     * 读不到它 —— 只有 Cookie 一条路的话，前端回填不了请求头，写请求全被 403（AUTH013）拦下，
     * 而只读请求正常，表现为「能看不能改」。因此登录与 {@code /api/v1/all/auth/info}
     * 都随响应体返回该值，前端缓存在内存中。跨站页面读不到响应体，双提交模型不变。</p>
     */
    private String csrfToken;
}
