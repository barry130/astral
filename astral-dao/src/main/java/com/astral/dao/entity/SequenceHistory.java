package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sequence_history")
public class SequenceHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("biz_key")
    @Size(max = 64)
    @NotNull
    private String bizKey;

    @TableField("sequence_type")
    @Size(max = 32)
    private String sequenceType;

    @TableField("sequence_value")
    @NotNull
    private Long sequenceValue;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
