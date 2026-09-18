package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存储文件夹（sys_storage_folder）——组织与授权边界
 */
@Data
@TableName("sys_storage_folder")
public class StorageFolderEntity {

    public static final String OWNER_ADMIN = "ADMIN";
    public static final String OWNER_USER = "USER";
    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long parentId;

    private String folderName;

    /** 展示路径（父路径 + 名称），仅展示用 */
    private String folderPath;

    private Long storageConfigId;

    /** ADMIN | USER | PLUGIN */
    private String ownerType;

    /** 所有者 ID（USER 时为用户 ID 字符串） */
    private String ownerId;

    /** 文件夹默认可见性：PRIVATE | PUBLIC */
    private String visibility;

    /** ENABLED | DISABLED */
    private String status;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private String updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
