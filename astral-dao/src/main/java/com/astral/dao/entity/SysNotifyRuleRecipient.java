package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_notify_rule_recipient")
public class SysNotifyRuleRecipient {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("channel_id")
    private Long channelId;

    @TableField("recipient_type")
    @Size(max = 16)
    private String recipientType;

    @TableField("recipient_value")
    @Size(max = 128)
    private String recipientValue;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
