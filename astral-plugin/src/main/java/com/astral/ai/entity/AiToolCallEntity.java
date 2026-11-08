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
@TableName("ai_tool_call")
public class AiToolCallEntity {

    @TableField("reconciled_by")
    private Long reconciledBy;

    @TableField("reconciled_at")
    private LocalDateTime reconciledAt;

    @TableField("reconciliation_note")
    private String reconciliationNote;

    @TableField("execution_token")
    private String executionToken;

    @TableField("provider_tool_call_id")
    private String providerToolCallId;

    @TableField("assistant_message_id")
    private Long assistantMessageId;

    @TableField("tool_message_id")
    private Long toolMessageId;

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("request_id")
    private Long requestId;

    @TableField("tool_code")
    @NotNull
    private String toolCode;

    @TableField("tool_name")
    private String toolName;

    @TableField("risk_level")
    private String riskLevel;

    private String status;

    @TableField("arguments_json")
    private String argumentsJson;

    @TableField("result_json")
    private String resultJson;

    @TableField("error_message")
    private String errorMessage;

    @TableField("approval_status")
    private String approvalStatus;

    @TableField("approved_by")
    private Long approvedBy;

    @TableField("approved_at")
    private LocalDateTime approvedAt;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private String source;

    @TableField("plan_hash")
    private String planHash;
}
