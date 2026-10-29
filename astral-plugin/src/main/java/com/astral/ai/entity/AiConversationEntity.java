package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话
 */
@Data
@TableName("ai_conversation")
public class AiConversationEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 会话键 */
    private String conversationKey;

    /** 智能体 ID */
    private Long agentId;

    /** 智能体版本 ID */
    private Long agentVersionId;

    /** 用户 ID */
    private Long userId;

    /** 客户端类型 */
    private String clientType;

    /** 标题 */
    private String title;

    /** 状态 */
    private String status;

    /** 消息数量 */
    private Integer messageCount;

    /** 最近消息时间 */
    private LocalDateTime lastMessageTime;

    /** 上下文快照 */
    private String contextJson;

    /** 是否置顶 */
    private Boolean pinned;

    /** 删除时间 */
    private LocalDateTime deletedAt;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
