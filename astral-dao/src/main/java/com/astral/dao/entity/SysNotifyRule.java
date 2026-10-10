package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_notify_rule")
public class SysNotifyRule {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("rule_name")
    @Size(max = 64)
    private String ruleName;

    @TableField("event_code")
    @Size(max = 64)
    private String eventCode;

    @Size(max = 16)
    private String channel;

    @TableField("template_id")
    private Long templateId;

    @TableField("recipient_type")
    @Size(max = 16)
    private String recipientType;

    @TableField("recipient_value")
    @Size(max = 128)
    private String recipientValue;

    @Size(max = 96)
    private String platform;

    private Integer enabled;

    @Size(max = 256)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
