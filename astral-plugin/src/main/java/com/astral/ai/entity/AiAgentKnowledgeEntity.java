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
 * AI 智能体知识绑定
 */
@Data
@TableName("ai_agent_knowledge")
public class AiAgentKnowledgeEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 智能体版本 ID */
    private Long agentVersionId;

    /** 知识源 ID */
    private Long knowledgeSourceId;

    /** 是否启用 */
    private Boolean enabled;

    /** 优先级 */
    private Integer priority;

    /** 召回数量 */
    private Integer retrievalTopK;

    /** 最低相关度 */
    private BigDecimal minScore;

    /** 引用策略 */
    private String citationPolicy;

    /** 过滤策略 */
    private String filterPolicyJson;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
