package com.astral.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 回复反馈请求体
 * <p>用户/管理员通用。</p>
 */
@Data
public class ReplyDto {

    /** 反馈ID */
    @NotNull(message = "反馈ID不能为空")
    private Long feedbackId;

    /** 回复内容 */
    @NotBlank(message = "回复内容不能为空")
    @Size(max = 2000, message = "回复内容最长2000字符")
    private String content;
}
