package com.astral.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** TOTP 动态验证码：仅启用了二次验证的账号需要；缺失时后端返回 AUTH010 提示前端补录 */
    private String totpCode;
}