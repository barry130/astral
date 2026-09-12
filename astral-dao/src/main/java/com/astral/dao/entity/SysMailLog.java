package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_mail_log")
public class SysMailLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("account_id")
    private Long accountId;

    @TableField("plugin_id")
    private String pluginId;

    private String scene;

    @TableField("to_email")
    private String toEmail;

    private String subject;

    private String content;

    private Integer status;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("send_time")
    private LocalDateTime sendTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
