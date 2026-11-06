package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_alert_rule")
public class AlertRule {

    @TableId(type = IdType.INPUT)
    private Long id;

    @Size(max = 64)
    @NotNull
    private String name;

    @Size(max = 32)
    @NotNull
    private String metric;

    @NotNull
    private Long threshold;

    @TableField("window_minutes")
    private Integer windowMinutes;

    @TableField("channel_id")
    @NotNull
    private Long channelId;

    @TableField("cooldown_minutes")
    private Integer cooldownMinutes;

    private Integer enabled;

    @TableField("last_fired_at")
    private LocalDateTime lastFiredAt;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
