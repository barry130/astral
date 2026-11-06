package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_plugin")
public class SysPlugin {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("plugin_id")
    @Size(max = 64)
    private String pluginId;

    private Integer enabled;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
