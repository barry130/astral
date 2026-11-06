package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
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
    @Size(max = 64)
    private String deviceId;

    @Size(max = 16)
    private String ut;

    @TableField("app_version")
    @Size(max = 32)
    private String appVersion;

    @Size(max = 128)
    private String model;

    @Size(max = 64)
    private String os;

    @TableField("last_ip")
    @Size(max = 64)
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
