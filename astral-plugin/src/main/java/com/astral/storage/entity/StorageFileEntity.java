package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存储文件（sys_storage_file）
 * <p>public_id 为对外稳定标识（不可预测）；provider_locator_json 为 Provider 专属定位信息
 * （Telegram: chatId/messageId/fileId/fileUniqueId），仅服务端与 Worker 链路使用，普通接口不回显。</p>
 */
@Data
@TableName("sys_storage_file")
public class StorageFileEntity {

    public static final String VISIBILITY_PRIVATE = "PRIVATE";
    public static final String VISIBILITY_PUBLIC = "PUBLIC";
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_DELETING = "DELETING";
    public static final String STATUS_DELETE_FAILED = "DELETE_FAILED";
    public static final String STATUS_DELETED = "DELETED";
    public static final String STATUS_ORPHAN = "ORPHAN_POSSIBLE";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 对外稳定文件标识（不可预测，用于 URL） */
    private String publicId;

    /** 上传凭证 ID（幂等键） */
    private String uploadId;

    private Long folderId;

    private Long storageConfigId;

    /** TELEGRAM */
    private String providerType;

    /** Provider 专属定位信息 JSON */
    private String providerLocatorJson;

    private String originalName;

    private String contentType;

    private Long sizeBytes;

    private String checksum;

    /** 内容/可见性版本，进入签名路径与缓存键；替换或转公开/私有时递增 */
    private Long contentVersion;

    private String visibility;

    /** AVAILABLE | DELETING | DELETE_FAILED | DELETED | ORPHAN_POSSIBLE | FAILED */
    private String status;

    /** USER | ADMIN | PLUGIN | WORKER */
    private String uploaderType;

    private String uploaderId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    private LocalDateTime deletedTime;
}
