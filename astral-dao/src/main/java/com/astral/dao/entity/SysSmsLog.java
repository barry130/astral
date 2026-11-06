package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_sms_log")
public class SysSmsLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("provider_id")
    private Long providerId;

    @TableField("plugin_id")
    @Size(max = 64)
    private String pluginId;

    @Size(max = 64)
    private String scene;

    @Size(max = 32)
    private String phone;

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
