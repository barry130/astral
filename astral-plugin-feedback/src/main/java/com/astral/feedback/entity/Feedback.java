package com.astral.feedback.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈主表
 * <p>问题/需求反馈，提交人为 APP 用户（sys_user.id，user_type='APP'）。</p>
 * <p>状态机：pending → received → resolved → published；任意状态可 → deprecated。</p>
 */
@Data
@TableName("sys_feedback")
@JsonIgnoreProperties(ignoreUnknown = true)
public class Feedback {

    /** 状态：提出 */
    public static final String STATUS_PENDING = "pending";
    /** 状态：已接收 */
    public static final String STATUS_RECEIVED = "received";
    /** 状态：已解决 */
    public static final String STATUS_RESOLVED = "resolved";
    /** 状态：已发布 */
    public static final String STATUS_PUBLISHED = "published";
    /** 状态：已废弃 */
    public static final String STATUS_DEPRECATED = "deprecated";

    /** 类型：问题 */
    public static final String TYPE_ISSUE = "issue";
    /** 类型：需求 */
    public static final String TYPE_REQUEST = "request";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 提交用户ID（sys_user.id，APP 用户） */
    private Long userId;

    /** 提交人用户名（JOIN sys_user 填充，非DB字段） */
    @TableField(exist = false)
    private String username;

    /** 提交人邮箱（JOIN sys_user 填充，非DB字段） */
    @TableField(exist = false)
    private String email;

    /** 类型：issue 问题 | request 需求 */
    private String type;

    /** 标题 */
    private String title;

    /** 内容 */
    private String content;

    /** 联系方式，可空 */
    private String contact;

    /** 状态：pending|received|resolved|published|deprecated */
    private String status;

    /** 是否公开（published 时 App 才展示） */
    private Boolean isPublic;

    /** 设备型号 */
    private String device;

    /** 系统版本 */
    private String os;

    /** App 版本（如 3.0.0） */
    private String appVersion;

    /** 平台：android | ios */
    private String platform;

    /** 提交 IP（服务端取） */
    private String ip;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 软删除时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deleteTime;
}
