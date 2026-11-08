package com.astral.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 状态变更请求体
 */
@Data
public class StatusDto {

    /** 目标状态：pending|received|resolved|published|deprecated */
    @NotBlank(message = "状态不能为空")
    private String status;
}
