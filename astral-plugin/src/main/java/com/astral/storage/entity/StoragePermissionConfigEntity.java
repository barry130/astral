package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 授权-存储配置绑定（sys_storage_permission_config）
 *
 * <p>授权行（sys_storage_folder_permission）可限定该被授权人上传使用的存储配置，
 * <b>必须是所属文件夹绑定集合的子集</b>（写入时校验）；为空 = 跟随文件夹绑定。</p>
 */
@Data
@TableName("sys_storage_permission_config")
public class StoragePermissionConfigEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 对应 sys_storage_folder_permission.id */
    private Long permissionId;

    private Long storageConfigId;

    /** 优先级（0–10000，小值优先，默认 100） */
    private Integer priority;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
