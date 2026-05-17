package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sequence_statistics")
public class SequenceStatistics {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("biz_key")
    private String bizKey;

    @TableField("current_value")
    private Long currentValue;

    @TableField("total_generate")
    private Long totalGenerate;

    @TableField("last_generate_time")
    private LocalDateTime lastGenerateTime;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

}
