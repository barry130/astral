package com.astral.cms.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 评论审核请求
 */
@Data
public class CommentAuditDto {

    /** 审核结果：approved|rejected */
    @NotBlank(message = "审核结果不能为空")
    private String status;
}
