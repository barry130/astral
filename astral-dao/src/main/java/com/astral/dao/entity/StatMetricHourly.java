package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("stat_metric_hourly")
public class StatMetricHourly {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("bucket_hour")
    private LocalDateTime bucketHour;

    private String ut;

    @TableField("app_version")
    private String appVersion;

    private Long pv;

    private Long visits;

    private Long launches;

    @TableField("total_duration_ms")
    private Long totalDurationMs;

    @TableField("error_count")
    private Long errorCount;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
