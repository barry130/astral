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
 * AI 额度策略
 */
@Data
@TableName("ai_quota_policy")
public class AiQuotaPolicyEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 策略编码 */
    private String policyCode;

    /** 策略名称 */
    private String name;

    /** 作用域 */
    private String scopeType;

    /** 智能体 ID */
    private Long agentId;

    /** 角色 ID */
    private Long roleId;

    /** 用户 ID */
    private Long userId;

    /** 每日请求上限 */
    private Integer dailyRequests;

    /** 每月请求上限 */
    private Integer monthlyRequests;

    /** 每日输入 Token */
    private Long dailyInputTokens;

    /** 每月输入 Token */
    private Long monthlyInputTokens;

    /** 每日输出 Token */
    private Long dailyOutputTokens;

    /** 每月输出 Token */
    private Long monthlyOutputTokens;

    /** 单次最大输入字符 */
    private Integer maxInputChars;

    /** 最大上下文 Token */
    private Integer maxContextTokens;

    /** 最大输出 Token */
    private Integer maxOutputTokens;

    /** 最大并发 */
    private Integer maxConcurrent;

    /** 月度费用上限 */
    private BigDecimal monthlyCostLimit;

    /** 是否启用 */
    private Boolean enabled;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
