package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_notify_rule_channel")
public class SysNotifyRuleChannel {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("rule_id")
    private Long ruleId;

    @Size(max = 16)
    private String channel;

    @TableField("template_id")
    private Long templateId;

    private Integer enabled;

    @TableField("daily_limit")
    private Integer dailyLimit;

    @TableField("daily_limit_scope")
    @Size(max = 16)
    private String dailyLimitScope;

    @Size(max = 96)
    private String platform;

    @Size(max = 256)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
