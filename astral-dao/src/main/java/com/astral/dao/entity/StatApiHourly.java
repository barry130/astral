package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    private String uri;

    private String method;

    private Integer status;

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
