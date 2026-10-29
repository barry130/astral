package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * AI 用量
 */
@Data
@TableName("ai_usage")
public class AiUsageEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 请求 ID */
    private Long requestId;

    /** 用户 ID */
    private Long userId;

    /** 智能体 ID */
    private Long agentId;

    /** 智能体版本 ID */
    private Long agentVersionId;

    /** 提供方 ID */
    private Long providerId;

    /** 模型 ID */
    private Long modelId;

    /** 输入 Token */
    private Integer inputTokens;

    /** 输出 Token */
    private Integer outputTokens;

    /** 预估费用 */
    private BigDecimal estimatedCost;

    /** 币种 */
    private String currency;

    /** 预占金额 */
    private BigDecimal reservationAmount;

    /** 结算金额 */
    private BigDecimal settledAmount;

    /** 结算状态 */
    private String status;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
