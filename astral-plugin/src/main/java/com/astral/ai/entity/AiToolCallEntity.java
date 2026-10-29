package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 工具调用
 */
@Data
@TableName("ai_tool_call")
public class AiToolCallEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 请求 ID */
    private Long requestId;

    /** 工具编码 */
    private String toolCode;

    /** 工具名称 */
    private String toolName;

    /** 风险等级 */
    private String riskLevel;

    /** 状态 */
    private String status;

    /** 参数 */
    private String argumentsJson;

    /** 结果 */
    private String resultJson;

    /** 错误信息 */
    private String errorMessage;

    /** 确认状态 */
    private String approvalStatus;

    /** 确认人 */
    private Long approvedBy;

    /** 确认时间 */
    private LocalDateTime approvedAt;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
