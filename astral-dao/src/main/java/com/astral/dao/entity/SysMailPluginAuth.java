package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_mail_plugin_auth")
public class SysMailPluginAuth {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("plugin_id")
    @Size(max = 32)
    private String pluginId;

    @TableField("plugin_name")
    @Size(max = 64)
    private String pluginName;

    @TableField("daily_limit")
    private Integer dailyLimit;

    @TableField("allowed_scenes")
    @Size(max = 512)
    private String allowedScenes;

    private Integer enabled;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
