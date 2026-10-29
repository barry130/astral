package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 智能体工具绑定
 */
@Data
@TableName("ai_agent_tool")
public class AiAgentToolEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 智能体版本 ID */
    private Long agentVersionId;

    /** 工具 ID */
    private Long toolId;

    /** 是否启用 */
    private Boolean enabled;

    /** 排序 */
    private Integer sort;

    /** 风险覆盖（只能提高） */
    private String riskOverride;

    /** 确认策略覆盖 */
    private String approvalPolicy;

    /** 参数结构覆盖 */
    private String argumentsSchemaOverride;

    /** 结果结构覆盖 */
    private String resultSchemaOverride;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
