package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_sms_provider")
public class SysSmsProvider {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("provider_name")
    @Size(max = 64)
    private String providerName;

    @TableField("provider_type")
    @Size(max = 16)
    private String providerType;

    @TableField("access_key")
    @Size(max = 128)
    private String accessKey;

    @TableField("access_secret")
    @Size(max = 256)
    private String accessSecret;

    @TableField("sign_name")
    @Size(max = 64)
    private String signName;

    @Size(max = 32)
    private String region;

    @Size(max = 128)
    private String endpoint;

    private Integer enabled;

    private Integer weight;

    @Size(max = 256)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
