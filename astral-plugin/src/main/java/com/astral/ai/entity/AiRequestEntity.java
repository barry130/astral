package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 请求
 */
@Data
@TableName("ai_request")
public class AiRequestEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 会话 ID */
    private Long conversationId;

    /** 智能体 ID */
    private Long agentId;

    /** 智能体版本 ID */
    private Long agentVersionId;

    /** 用户 ID */
    private Long userId;

    /** 客户端类型 */
    private String clientType;

    /** 请求状态 */
    private String status;

    /** 幂等键 */
    private String idempotencyKey;

    /** 提供方 ID */
    private Long providerId;

    /** 模型 ID */
    private Long modelId;

    /** 提示词版本 ID */
    private Long promptVersionId;

    /** 输入快照 */
    private String inputSnapshot;

    /** 上下文快照 */
    private String contextSnapshot;

    /** 版本配置快照 */
    private String configurationSnapshot;

    /** 知识策略快照 */
    private String knowledgeSnapshot;

    /** 工具策略快照 */
    private String toolPolicySnapshot;

    /** 上下文策略快照 */
    private String contextPolicySnapshot;

    /** 响应结构快照 */
    private String responseSchemaSnapshot;

    /** 安全策略快照 */
    private String safetyPolicySnapshot;

    /** 额度策略快照 */
    private String quotaPolicySnapshot;

    /** 错误码 */
    private String errorCode;

    /** 错误摘要 */
    private String errorSummary;

    /** 取消原因 */
    private String cancelReason;

    /** 输入 Token */
    private Integer tokenIn;

    /** 输出 Token */
    private Integer tokenOut;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 首 Token 时间 */
    private LocalDateTime firstTokenAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
