package com.astral.cms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 匿名评论提交请求（App 端公开接口）
 */
@Data
public class CommentSubmitDto {

    /** 目标类型：post|page */
    @NotBlank(message = "目标类型不能为空")
    private String targetType;

    /** 目标ID（文章/页面） */
    @NotNull(message = "目标ID不能为空")
    private Long targetId;

    /** 昵称 */
    @NotBlank(message = "昵称不能为空")
    @Size(max = 64, message = "昵称最长 64 字")
    private String nickname;

    /** 邮箱（可选，仅管理端可见） */
    @Email(message = "邮箱格式不正确")
    @Size(max = 128, message = "邮箱最长 128 字")
    private String email;

    /** 评论内容（纯文本） */
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 2000, message = "评论内容最长 2000 字")
    private String content;
}
