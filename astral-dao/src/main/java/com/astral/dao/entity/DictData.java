package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_dict_data")
public class DictData {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("dict_type_id")
    @NotNull
    private Long dictTypeId;

    @TableField("dict_label")
    @Size(max = 128)
    @NotNull
    private String dictLabel;

    @TableField("dict_value")
    @Size(max = 128)
    @NotNull
    private String dictValue;

    @TableField("dict_sort")
    private Integer dictSort;

    @TableField("css_class")
    @Size(max = 128)
    private String cssClass;

    @TableField("list_class")
    @Size(max = 128)
    private String listClass;

    @TableField("is_default")
    private Integer isDefault;

    private Integer status;

    @Size(max = 256)
    private String description;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
