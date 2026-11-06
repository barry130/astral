package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_dict_type")
public class DictType {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("dict_code")
    @Size(max = 64)
    @NotNull
    private String dictCode;

    @TableField("dict_name")
    @Size(max = 128)
    @NotNull
    private String dictName;

    @TableField("data_type")
    @Size(max = 32)
    private String dataType;

    @TableField("jdbc_type")
    @Size(max = 32)
    private String jdbcType;

    @TableField("data_length")
    private Integer dataLength;

    @Size(max = 256)
    private String description;

    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
