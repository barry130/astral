package com.astral.qt.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 公告已读记录（用于消息中心已读/未读与红点）
 */
@Data
@TableName("qt_app_notice_read")
public class QtAppNoticeRead {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 公告 id */
    private Long noticeId;

    /** 用户 id */
    private Long userId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    // 本表无 create_time 列，此注解用于触发全局 MetaObjectHandler 的主键自动填充；字段值仍由调用方显式赋
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime readTime;
}
