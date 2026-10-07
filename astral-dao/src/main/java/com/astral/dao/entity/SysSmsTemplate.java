package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_sms_template")
public class SysSmsTemplate {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("template_code")
    @Size(max = 64)
    private String templateCode;

    @TableField("template_name")
    @Size(max = 64)
    private String templateName;

    @TableField("event_code")
    @Size(max = 64)
    private String eventCode;

    @TableField("provider_template_code")
    @Size(max = 64)
    private String providerTemplateCode;

    @TableField("sign_name")
    @Size(max = 64)
    private String signName;

    @TableField("content_sample")
    private String contentSample;

    @TableField("audit_status")
    private Integer auditStatus;

    @TableField("audit_remark")
    @Size(max = 256)
    private String auditRemark;

    @Size(max = 256)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
