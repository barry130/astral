package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName("sequence_history")
public class SequenceHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("biz_key")
    private String bizKey;

    @TableField("sequence_type")
    private String sequenceType;

    @TableField("sequence_value")
    private Long sequenceValue;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
