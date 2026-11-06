package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("config_name")
    @Size(max = 128)
    @NotNull
    private String configName;

    @TableField("config_key")
    @Size(max = 128)
    @NotNull
    private String configKey;

    @TableField("config_value")
    @Size(max = 512)
    @NotNull
    private String configValue;

    @TableField("config_type")
    private Integer configType;

    @Size(max = 256)
    private String description;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
