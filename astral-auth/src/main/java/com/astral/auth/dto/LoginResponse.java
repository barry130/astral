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
     * 管理端 CSRF 令牌（双提交用）。
     *
     * <p>仅管理端登录返回非空：令牌已由服务端同时写入非 HttpOnly 的 {@code astral_csrf} Cookie，
     * 前端把它原样回填到 {@code X-CSRF-Token} 请求头即可。轻听 App 走请求头认证，恒为 null。</p>
     */
    private String csrfToken;
}
