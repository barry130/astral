package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sequence_segment")
public class SequenceSegment {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("biz_key")
    @Size(max = 64)
    private String bizKey;

    @TableField("min_value")
    private Long minValue;

    @TableField("max_value")
    private Long maxValue;

    @TableField("current_max_value")
    private Long currentMaxValue;

    private Integer step;

    private Integer version;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
