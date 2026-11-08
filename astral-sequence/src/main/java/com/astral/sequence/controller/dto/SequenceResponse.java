package com.astral.sequence.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 序列号生成响应 DTO
 * <p>
 * 封装序列号生成的响应数据，返回给客户端。
 * </p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SequenceResponse {
    /** 业务键，标识该序列号所属的业务 */
    private String bizKey;
    /** 使用的生成器类型 */
    private String type;
    /** 生成的序列号值；批量请求时为最后一个有效序列号。字符串传输，避免 Snowflake 64 位值在 JS 中丢精度 */
    private String sequence;
    /** 批量请求时逗号分隔的完整序列号列表；单条请求为 null */
    private String values;
    /** 响应时间戳（毫秒），用于客户端计算延迟 */
    private Long timestamp;
}
