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
 * AI 智能体版本
 */
@Data
@TableName("ai_agent_version")
public class AiAgentVersionEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 智能体 ID */
    private Long agentId;

    /** 版本号 */
    private Integer versionNo;

    /** 版本名称 */
    private String versionName;

    /** 版本状态 */
    private String status;

    /** 提供方路由 ID */
    private Long providerRouteId;

    /** 系统提示词版本 ID */
    private Long systemPromptVersionId;

    /** 客户端快照 */
    private String clientTypesSnapshot;

    /** 入口配置快照 */
    private String entryConfigSnapshot;

    /** 上下文策略 */
    private String contextPolicyJson;

    /** 响应结构 */
    private String responseSchemaJson;

    /** 工具策略 */
    private String toolPolicyJson;

    /** 知识策略 */
    private String knowledgePolicyJson;

    /** 模型参数 */
    private String modelParametersJson;

    /** 安全策略 */
    private String safetyPolicyJson;

    /** 额度覆盖 */
    private String quotaOverrideJson;

    /** 校验结果 */
    private String validationSummaryJson;

    /** 发布人 */
    private Long publishedBy;

    /** 发布时间 */
    private LocalDateTime publishedAt;

    /** 回滚来源版本 */
    private Long rollbackFromVersionId;

    /** 创建人 */
    private Long createdBy;

    /** 更新人 */
    private Long updatedBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
