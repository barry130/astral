package com.astral.system.notice.dto;

import lombok.Data;

import java.util.List;

/**
 * 已读回执请求体（本期后端仅记日志，不落表）。
 * <p>保持原插件版字段名 {@code ids} 不变：已发布客户端提交的就是这个结构。</p>
 */
@Data
public class ReadAckDto {

    /** 已读的通知 ID 列表 */
    private List<Long> ids;
}
