package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 反馈
 */
@Data
@TableName("ai_feedback")
public class AiFeedbackEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 会话 ID */
    private Long conversationId;

    /** 消息 ID */
    private Long messageId;

    /** 请求 ID */
    private Long requestId;

    /** 用户 ID */
    private Long userId;

    /** 智能体 ID */
    private Long agentId;

    /** 反馈类型 */
    private String feedbackType;

    /** 原因编码 */
    private String reasonCode;

    /** 说明 */
    private String comment;

    /** 附加信息 */
    private String metadataJson;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
