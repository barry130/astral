package com.astral.cms.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 站点设置保存请求（KV 全量提交）
 */
@Data
public class SettingSaveDto {

    /** 配置键值对；仅接受已知键，未知键报错 */
    @NotNull(message = "配置不能为空")
    private Map<String, String> settings;
}
