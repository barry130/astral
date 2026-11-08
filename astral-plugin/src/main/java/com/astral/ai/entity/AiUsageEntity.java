package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_usage")
public class AiUsageEntity {

    @TableField("reconciled_by")
    private Long reconciledBy;

    @TableField("reconciled_at")
    private LocalDateTime reconciledAt;

    @TableField("reconciliation_note")
    private String reconciliationNote;

    @TableField("stage_key")
    private String stageKey;

    @TableField("day_row_id")
    private Long dayRowId;

    @TableField("month_row_id")
    private Long monthRowId;

    @TableField("reserved_input_tokens")
    private Integer reservedInputTokens;

    @TableField("reserved_output_tokens")
    private Integer reservedOutputTokens;

    @TableField("input_price")
    private BigDecimal inputPrice;

    @TableField("output_price")
    private BigDecimal outputPrice;

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("request_id")
    private Long requestId;

    @TableField("user_id")
    private Long userId;

    @TableField("agent_id")
    private Long agentId;

    @TableField("agent_version_id")
    private Long agentVersionId;

    @TableField("provider_id")
    private Long providerId;

    @TableField("model_id")
    private Long modelId;

    @TableField("input_tokens")
    private Integer inputTokens;

    @TableField("output_tokens")
    private Integer outputTokens;

    @TableField("estimated_cost")
    private BigDecimal estimatedCost;

    private String currency;

    @TableField("reservation_amount")
    private BigDecimal reservationAmount;

    @TableField("settled_amount")
    private BigDecimal settledAmount;

    private String status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
