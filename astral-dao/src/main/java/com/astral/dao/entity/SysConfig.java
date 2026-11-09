package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

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
