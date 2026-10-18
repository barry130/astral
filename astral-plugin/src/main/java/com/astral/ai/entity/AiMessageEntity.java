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
 * AI 消息
 */
@Data
@TableName("ai_message")
public class AiMessageEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 会话 ID */
    private Long conversationId;

    /** 请求 ID */
    private Long requestId;

    /** 父消息 ID */
    private Long parentMessageId;

    /** 角色 */
    private String role;

    /** 内容 */
    private String content;

    /** 内容类型 */
    private String contentType;

    /** 状态 */
    private String status;

    /** 结构化块 */
    private String blocksJson;

    /** 引用 */
    private String citationsJson;

    /** 模型名称 */
    private String modelName;

    /** 输入 Token */
    private Integer inputTokens;

    /** 输出 Token */
    private Integer outputTokens;

    /** 结束原因 */
    private String finishReason;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
