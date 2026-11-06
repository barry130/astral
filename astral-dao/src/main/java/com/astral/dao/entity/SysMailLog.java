package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
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
    @Size(max = 32)
    private String pluginId;

    @Size(max = 64)
    private String scene;

    @TableField("to_email")
    @Size(max = 128)
    private String toEmail;

    @Size(max = 256)
    private String subject;

    private String content;

    private Integer status;

    @TableField("error_msg")
    @Size(max = 512)
    private String errorMsg;

    @TableField("send_time")
    private LocalDateTime sendTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
