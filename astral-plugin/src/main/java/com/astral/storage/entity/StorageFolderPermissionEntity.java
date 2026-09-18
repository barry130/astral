package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件夹授权（sys_storage_folder_permission）
 * <p>permissions 为扁平权限集合，逗号分隔：READ,UPLOAD,UPDATE,DELETE,MANAGE。
 * Scope 别名（storage:file:upload 等）在校验时映射到该集合。</p>
 */
@Data
@TableName("sys_storage_folder_permission")
public class StorageFolderPermissionEntity {

    public static final String SUBJECT_USER = "USER";
    public static final String SUBJECT_PLUGIN = "PLUGIN";
    public static final String PERM_READ = "READ";
    public static final String PERM_UPLOAD = "UPLOAD";
    public static final String PERM_UPDATE = "UPDATE";
    public static final String PERM_DELETE = "DELETE";
    public static final String PERM_MANAGE = "MANAGE";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long folderId;

    /** USER | PLUGIN */
    private String subjectType;

    /** 主体 ID（用户 ID 或 pluginId） */
    private String subjectId;

    /** 逗号分隔权限集合 */
    private String permissions;

    /** 可选过期时间 */
    private LocalDateTime expiresTime;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 撤销时间（非空 = 已撤销） */
    private LocalDateTime revokedTime;
}
