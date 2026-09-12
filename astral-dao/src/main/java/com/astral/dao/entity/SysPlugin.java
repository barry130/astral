package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_plugin")
public class SysPlugin {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("plugin_id")
    private String pluginId;

    private Integer enabled;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

}
