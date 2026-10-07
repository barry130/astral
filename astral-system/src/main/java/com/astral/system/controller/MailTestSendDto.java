package com.astral.system.controller;

import java.util.Map;

import lombok.Data;

/**
 * 模板试发入参
 */
@Data
public class MailTestSendDto {

    /** 收件邮箱（必填） */
    private String toEmail;

    /** 发信账户ID；不传则按权重在启用账户中自动选择 */
    private Long accountId;

    /** 模板变量值（按模板 variables 声明的变量名传入） */
    private Map<String, String> variables;
}
