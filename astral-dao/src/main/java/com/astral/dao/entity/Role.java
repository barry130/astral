package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

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

    @TableField("is_super")
    private Integer isSuper;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.UPDATE)
    private LocalDateTime updateTime;
}
