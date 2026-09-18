package com.astral.qt.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class QtSendEmailDto {

    @NotBlank(message = "邮箱地址不能为空")
    @Email(message = "邮箱格式不正确")
    private String email;

    @NotBlank(message = "邮件业务标识不能为空")
    private String body;
}