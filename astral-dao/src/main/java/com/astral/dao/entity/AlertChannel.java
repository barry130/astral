package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_alert_channel")
public class AlertChannel {

    @TableId(type = IdType.INPUT)
    private Long id;

    @Size(max = 64)
    @NotNull
    private String name;

    @Size(max = 20)
    @NotNull
    private String type;

    private String config;

    private Integer enabled;

    @Size(max = 255)
    private String remark;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
