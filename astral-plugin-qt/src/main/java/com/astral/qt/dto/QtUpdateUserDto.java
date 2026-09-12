package com.astral.qt.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QtUpdateUserDto {

    /** 可选：不为空则更新密码 */
    @Size(min = 6, max = 18, message = "密码长度为6-18")
    private String password;

    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "头像地址不能为空")
    private String avatar;

    @NotBlank(message = "昵称不能为空")
    private String nickname;
}