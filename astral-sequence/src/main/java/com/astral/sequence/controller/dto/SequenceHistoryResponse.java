package com.astral.sequence.controller.dto;

import com.astral.dao.entity.SequenceHistory;
import java.time.LocalDateTime;

/**
 * 序列生成历史响应。
 * <p>
 * 序列值使用字符串传输：Snowflake 生成的 64 位 ID 超过 JavaScript 的
 * Number.MAX_SAFE_INTEGER（2^53-1），以 JSON number 返回会在前端被静默舍入，
 * 展示与复制出的序列号与实际值不一致。
 * </p>
 *
 * @param id            历史记录 ID
 * @param bizKey        业务键
 * @param sequenceType  序列类型
 * @param sequenceValue 序列值（字符串，避免前端精度丢失）
 * @param createTime    创建时间
 */
public record SequenceHistoryResponse(
        Long id, String bizKey, String sequenceType, String sequenceValue, LocalDateTime createTime) {

    /**
     * 从实体转换为响应。
     *
     * @param history 历史实体
     * @return 响应对象
     */
    public static SequenceHistoryResponse from(SequenceHistory history) {
        return new SequenceHistoryResponse(
                history.getId(),
                history.getBizKey(),
                history.getSequenceType(),
                history.getSequenceValue() == null ? null : Long.toString(history.getSequenceValue()),
                history.getCreateTime());
    }
}
