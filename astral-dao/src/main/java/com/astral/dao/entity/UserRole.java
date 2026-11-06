package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

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
