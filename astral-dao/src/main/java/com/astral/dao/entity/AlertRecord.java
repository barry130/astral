package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_alert_record")
public class AlertRecord {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("rule_id")
    @NotNull
    private Long ruleId;

    @TableField("rule_name")
    @Size(max = 64)
    private String ruleName;

    @TableField("channel_id")
    private Long channelId;

    @TableField("channel_name")
    @Size(max = 64)
    private String channelName;

    @Size(max = 256)
    private String title;

    private String content;

    @TableField("metric_value")
    private Long metricValue;

    @Size(max = 16)
    @NotNull
    private String status;

    @TableField("error_msg")
    @Size(max = 512)
    private String errorMsg;

    @TableField(value = "fired_at", fill = FieldFill.INSERT)
    private LocalDateTime firedAt;

}
