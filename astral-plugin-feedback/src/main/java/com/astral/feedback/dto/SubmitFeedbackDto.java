package com.astral.feedback.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交反馈请求体
 * <p>服务端自动补 user_id（拦截器注入）、device/os/app_version/platform（请求头）、ip。</p>
 */
@Data
public class SubmitFeedbackDto {

    /** 类型：issue 问题 | request 需求 */
    @NotBlank(message = "类型不能为空")
    private String type;

    /** 标题 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 128, message = "标题最长128字符")
    private String title;

    /** 内容 */
    @NotBlank(message = "内容不能为空")
    @Size(max = 5000, message = "内容最长5000字符")
    private String content;

    /** 联系方式（可空） */
    @Size(max = 64, message = "联系方式最长64字符")
    private String contact;
}
