package com.astral.feedback.dto;

import lombok.Data;

/**
 * 已读回执请求体（本期后端仅记日志，不落表）
 */
@Data
public class ReadAckDto {

    /** 已读的通知ID列表 */
    private java.util.List<Long> ids;
}
