package com.astral.feedback.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈回复表（双向、扁平）
 * <p>用户与管理员对同一条反馈的对话，全部用 user_id（管理员也是 sys_user 用户）。</p>
 * <p>昵称展示时 LEFT JOIN sys_user 取 nickname；前端可用 user_type 渲染「官方」角标。</p>
 */
@Data
@TableName("sys_feedback_reply")
@JsonIgnoreProperties(ignoreUnknown = true)
public class FeedbackReply {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属反馈ID */
    private Long feedbackId;

    /** 发送者ID（用户/管理员都是 sys_user.id） */
    private Long userId;

    /** 回复内容 */
    private String content;

    /** 回复时间（用于日期筛选） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    // 本表无 create_time 列，此注解用于触发全局 MetaObjectHandler 的主键自动填充；字段值仍由调用方显式赋
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime replyTime;

    // ==================== 非数据库字段（JOIN 结果） ====================

    /** 发送者昵称（JOIN sys_user 填充，不落库） */
    @TableField(exist = false)
    private String nickname;

    /** 发送者用户类型（ADMIN/APP，JOIN sys_user 填充） */
    @TableField(exist = false)
    private String userType;
}
