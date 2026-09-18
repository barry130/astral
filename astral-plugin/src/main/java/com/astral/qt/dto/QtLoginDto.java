package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class QtLoginDto {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 30, message = "用户名长度为3-30")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;
}