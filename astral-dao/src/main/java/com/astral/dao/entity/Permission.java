package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_permission")
public class Permission {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("permission_code")
    @Size(max = 64)
    @NotNull
    private String permissionCode;

    @TableField("permission_name")
    @Size(max = 128)
    @NotNull
    private String permissionName;

    @Size(max = 256)
    private String url;

    @Size(max = 16)
    private String method;

    @TableField("parent_id")
    private Long parentId;

    private Integer type;

    @Size(max = 64)
    private String icon;

    private Integer sort;

    private Integer status;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

    @TableField(exist = false)
    private List<Permission> children;

}
