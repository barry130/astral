package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_notice")
public class SysNotice {

    @TableId(type = IdType.INPUT)
    private Long id;

    @Size(max = 64)
    private String channel;

    @TableField("notice_type")
    @Size(max = 16)
    private String noticeType;

    @Size(max = 64)
    private String scene;

    @TableField("user_id")
    private Long userId;

    @TableField("feedback_id")
    private Long feedbackId;

    private Long display;

    @Size(max = 128)
    @NotNull
    private String title;

    @Size(max = 65535)
    private String content;

    @Size(max = 512)
    private String url;

    @TableField("is_show")
    private Long isShow;

    @TableField("is_top")
    private Long isTop;

    @TableField("dialog_closable")
    private Long dialogClosable;

    @TableField("first_login_only")
    private Long firstLoginOnly;

    private Long marquee;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("effective_start")
    private LocalDateTime effectiveStart;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField("effective_end")
    private LocalDateTime effectiveEnd;

    @TableField("version_min")
    private Long versionMin;

    @TableField("version_max")
    private Long versionMax;

    @Size(max = 16)
    private String audience;

    @TableField("read_time")
    private LocalDateTime readTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Boolean read;

}
