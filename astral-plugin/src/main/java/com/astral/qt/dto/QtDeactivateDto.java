package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * App 用户自助注销请求
 */
@Data
public class QtDeactivateDto {

    @NotBlank(message = "密码不能为空")
    private String password;
}
