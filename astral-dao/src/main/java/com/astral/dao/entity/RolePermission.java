package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_role_permission")
public class RolePermission {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("role_id")
    @NotNull
    private Long roleId;

    @TableField("permission_id")
    @NotNull
    private Long permissionId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
