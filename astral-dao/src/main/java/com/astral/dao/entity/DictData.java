package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_dict_data")
public class DictData {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("dict_type_id")
    private Long dictTypeId;

    @TableField("dict_label")
    private String dictLabel;

    @TableField("dict_value")
    private String dictValue;

    @TableField("dict_sort")
    private Integer dictSort;

    @TableField("css_class")
    private String cssClass;

    @TableField("list_class")
    private String listClass;

    @TableField("is_default")
    private Integer isDefault;

    private Integer status;

    private String description;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
