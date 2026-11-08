package com.astral.system.notice.dto;

import java.util.List;
import lombok.Data;

/**
 * 已读回执请求体（本人点对点消息持久化 read_time，广播由客户端缓存）。
 * <p>保持原插件版字段名 {@code ids} 不变：已发布客户端提交的就是这个结构。</p>
 */
@Data
public class ReadAckDto {

    /** 已读的通知 ID 列表 */
    private List<Long> ids;
}
