package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("stat_error_log")
public class StatErrorLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    @Size(max = 64)
    private String fingerprint;

    @TableField("error_type")
    @Size(max = 16)
    private String errorType;

    @Size(max = 1024)
    private String message;

    private String stack;

    @Size(max = 256)
    private String page;

    @Size(max = 16)
    private String ut;

    @TableField("app_version")
    @Size(max = 32)
    private String appVersion;

    @Size(max = 64)
    private String os;

    @Size(max = 128)
    private String model;

    @TableField("device_id")
    @Size(max = 64)
    private String deviceId;

    @Size(max = 64)
    private String release;

    @Size(max = 64)
    private String ip;

    @TableField("occur_time")
    private LocalDateTime occurTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
