package com.astral.qt.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QtRegisterDto {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 30, message = "用户名长度为3-30")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 18, message = "密码长度为6-18")
    private String password;

    @NotBlank(message = "确认密码不能为空")
    private String passwordConfirm;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    private String avatar;

    private String nickname;

    private String deviceId;
}