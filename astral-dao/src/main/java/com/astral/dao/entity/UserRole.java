package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

import java.util.List;

@Data
@TableName("sys_user_role")
public class UserRole {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("user_id")
    @NotNull
    private Long userId;

    @TableField("role_id")
    @NotNull
    private Long roleId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

}
