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
 * AI 用户额度
 */
@Data
@TableName("ai_user_quota")
public class AiUserQuotaEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 用户 ID */
    private Long userId;

    /** 智能体 ID */
    private Long agentId;

    /** 策略 ID */
    private Long policyId;

    /** 周期类型 */
    private String periodType;

    /** 周期开始 */
    private LocalDate periodStart;

    /** 周期结束 */
    private LocalDate periodEnd;

    /** 请求数 */
    private Integer requestCount;

    /** 输入 Token */
    private Long inputTokens;

    /** 输出 Token */
    private Long outputTokens;

    /** 预占费用 */
    private BigDecimal reservedCost;

    /** 结算费用 */
    private BigDecimal settledCost;

    /** 版本号 */
    private Integer version;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
