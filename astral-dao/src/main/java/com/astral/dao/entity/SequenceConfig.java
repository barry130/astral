package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("sequence_config")
public class SequenceConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("biz_key")
    private String bizKey;

    @TableField("sequence_type")
    private String sequenceType;

    private Integer step;

    @TableField("date_format")
    private String dateFormat;

    private String prefix;

    private String suffix;

    @TableField("min_value")
    private Long minValue;

    private Boolean enabled;

    private String description;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    @TableField("current_value")
    private Long currentValue;

    @TableField("total_generate")
    private Long totalGenerate;

}
