package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("stat_device")
public class StatDevice {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("device_id")
    private String deviceId;

    private String ut;

    @TableField("app_version")
    private String appVersion;

    private String model;

    private String os;

    @TableField("last_ip")
    private String lastIp;

    @TableField("first_date")
    private LocalDate firstDate;

    @TableField("last_date")
    private LocalDate lastDate;

    @TableField("last_active_time")
    private LocalDateTime lastActiveTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
