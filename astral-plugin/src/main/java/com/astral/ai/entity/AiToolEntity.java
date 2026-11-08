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
@TableName("ai_tool")
public class AiToolEntity {

    @TableField("executor_type")
    private String executorType;

    @TableField("executor_config")
    private String executorConfig;

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("tool_code")
    @NotNull
    private String toolCode;

    @NotNull
    private String name;

    private String description;

    @TableField("handler_key")
    @NotNull
    private String handlerKey;

    @TableField("client_types")
    private String clientTypes;

    @TableField("permission_codes")
    private String permissionCodes;

    @TableField("risk_level")
    private String riskLevel;

    @TableField("approval_policy")
    private String approvalPolicy;

    @TableField("arguments_schema")
    private String argumentsSchema;

    @TableField("result_schema")
    private String resultSchema;

    @TableField("side_effects")
    private String sideEffects;

    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField("updated_by")
    private Long updatedBy;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
