package com.astral.cms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 内容状态变更请求（文章/页面通用）
 */
@Data
public class ContentStatusDto {

    /** 目标状态：draft|published|hidden */
    @NotBlank(message = "状态不能为空")
    private String status;
}
