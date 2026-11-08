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
@TableName("sequence_config")
public class SequenceConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("biz_key")
    @Size(max = 64)
    @NotNull
    private String bizKey;

    @TableField("sequence_type")
    @Size(max = 32)
    @NotNull
    private String sequenceType;

    private Integer step;

    @TableField("date_format")
    @Size(max = 32)
    private String dateFormat;

    @Size(max = 32)
    private String prefix;

    @Size(max = 32)
    private String suffix;

    @TableField("min_value")
    private Long minValue;

    private Boolean enabled;

    @Size(max = 256)
    private String description;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private Long currentValue;
}
