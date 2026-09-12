package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("stat_page_hourly")
public class StatPageHourly {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("bucket_hour")
    private LocalDateTime bucketHour;

    private String ut;

    private String page;

    private Long pv;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
