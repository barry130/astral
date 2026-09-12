package com.astral.feedback.dto;

import lombok.Data;

/**
 * 公开状态变更请求体
 */
@Data
public class PublicDto {

    /** 是否公开 */
    private Boolean isPublic;
}
