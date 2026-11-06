package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("stat_api_hourly")
public class StatApiHourly {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("bucket_hour")
    private LocalDateTime bucketHour;

    @Size(max = 256)
    private String uri;

    @Size(max = 8)
    private String method;

    private Integer status;

    @Size(max = 16)
    private String ut;

    @TableField("app_version")
    @Size(max = 32)
    private String appVersion;

    @TableField("call_count")
    private Long callCount;

    @TableField("sum_ms")
    private Long sumMs;

    @TableField("max_ms")
    private Integer maxMs;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
