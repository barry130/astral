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
}