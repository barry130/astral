package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_api_statistics")
public class ApiStatistics {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("api_path")
    private String apiPath;

    @TableField("api_method")
    private String apiMethod;

    @TableField("call_count")
    private Long callCount;

    @TableField("success_count")
    private Long successCount;

    @TableField("failure_count")
    private Long failureCount;

    @TableField("total_time")
    private Long totalTime;

    @TableField("avg_time")
    private Long avgTime;

    @TableField("max_time")
    private Long maxTime;

    @TableField("stat_date")
    private LocalDate statDate;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;

}
