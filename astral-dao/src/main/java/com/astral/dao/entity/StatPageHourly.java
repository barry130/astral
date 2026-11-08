package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("stat_page_hourly")
public class StatPageHourly {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("bucket_hour")
    private LocalDateTime bucketHour;

    @Size(max = 16)
    private String ut;

    @Size(max = 256)
    private String page;

    private Long pv;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
