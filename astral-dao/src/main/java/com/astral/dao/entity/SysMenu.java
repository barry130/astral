package com.astral.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_menu")
public class SysMenu {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("parent_id")
    private Long parentId;

    @Size(max = 64)
    @NotNull
    private String name;

    @Size(max = 64)
    private String icon;

    @Size(max = 256)
    private String path;

    @Size(max = 128)
    private String permission;

    private Integer sort;

    private Integer visible;

    private Integer type;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
