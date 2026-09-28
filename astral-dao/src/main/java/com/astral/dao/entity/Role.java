package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_role")
public class Role {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("role_code")
    @Size(max = 64)
    @NotNull
    private String roleCode;

    @TableField("role_name")
    @Size(max = 128)
    @NotNull
    private String roleName;

    @Size(max = 256)
    private String description;

    private Integer status;

    private Integer sort;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;

}
