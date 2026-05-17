package com.astral.auth.dto;

import lombok.Data;

/**
 * 登录请求DTO
 * <p>
 * 封装用户登录时提交的凭据信息。
 * </p>
 */
@Data
public class LoginRequest {
    /** 用户名 */
    private String username;
    /** 密码（明文，服务端会使用BCrypt进行校验） */
    private String password;
}