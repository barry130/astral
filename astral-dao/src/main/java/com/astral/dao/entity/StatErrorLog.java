package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("stat_error_log")
public class StatErrorLog {

    @TableId(type = IdType.INPUT)
    private Long id;

    private String fingerprint;

    @TableField("error_type")
    private String errorType;

    private String message;

    private String stack;

    private String page;

    private String ut;

    @TableField("app_version")
    private String appVersion;

    private String os;

    private String model;

    @TableField("device_id")
    private String deviceId;

    private String release;

    private String ip;

    @TableField("occur_time")
    private LocalDateTime occurTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
