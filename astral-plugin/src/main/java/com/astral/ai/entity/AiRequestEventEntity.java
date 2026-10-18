package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 请求事件
 */
@Data
@TableName("ai_request_event")
public class AiRequestEventEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 请求 ID */
    private Long requestId;

    /** 事件序号 */
    private Long sequenceNo;

    /** 事件类型 */
    private String eventType;

    /** 事件数据 */
    private String dataJson;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
