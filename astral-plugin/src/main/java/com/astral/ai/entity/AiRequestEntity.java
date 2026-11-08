package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ai_request")
public class AiRequestEntity {

    @TableField("worker_token")
    private String workerToken;

    @TableField("lease_until")
    private LocalDateTime leaseUntil;

    @TableField("quota_counted")
    private Boolean quotaCounted;

    @TableField("quota_active")
    private Boolean quotaActive;

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("conversation_id")
    @NotNull
    private Long conversationId;

    @TableField("agent_id")
    @NotNull
    private Long agentId;

    @TableField("agent_version_id")
    @NotNull
    private Long agentVersionId;

    @TableField("user_id")
    @NotNull
    private Long userId;

    @TableField("client_type")
    private String clientType;

    private String status;

    @TableField("idempotency_key")
    private String idempotencyKey;

    @TableField("provider_id")
    private Long providerId;

    @TableField("model_id")
    private Long modelId;

    @TableField("prompt_version_id")
    private Long promptVersionId;

    @TableField("input_snapshot")
    private String inputSnapshot;

    @TableField("context_snapshot")
    private String contextSnapshot;

    @TableField("configuration_snapshot")
    private String configurationSnapshot;

    @TableField("knowledge_snapshot")
    private String knowledgeSnapshot;

    @TableField("tool_policy_snapshot")
    private String toolPolicySnapshot;

    @TableField("context_policy_snapshot")
    private String contextPolicySnapshot;

    @TableField("response_schema_snapshot")
    private String responseSchemaSnapshot;

    @TableField("safety_policy_snapshot")
    private String safetyPolicySnapshot;

    @TableField("quota_policy_snapshot")
    private String quotaPolicySnapshot;

    @TableField("error_code")
    private String errorCode;

    @TableField("error_summary")
    private String errorSummary;

    @TableField("cancel_reason")
    private String cancelReason;

    @TableField("token_in")
    private Integer tokenIn;

    @TableField("token_out")
    private Integer tokenOut;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("first_token_at")
    private LocalDateTime firstTokenAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
