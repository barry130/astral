package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存储配置（sys_storage_config）
 * <p>Telegram Worker 直连架构：Bot Token 保存在 Cloudflare Worker Secret，
 * 本表只保存非敏感连接信息；credential 列为后续 R2/S3 Provider 预留（当前未建）。</p>
 */
@Data
@TableName("sys_storage_config")
public class StorageConfigEntity {

    public static final String PROVIDER_TELEGRAM = "TELEGRAM";
    public static final String PROVIDER_R2 = "R2";
    public static final String PROVIDER_S3 = "S3_COMPATIBLE";
    public static final String PROVIDER_COS = "COS";
    public static final String PROVIDER_OSS = "OSS";
    public static final String PROVIDER_QINIU = "QINIU";
    public static final String PROVIDER_UPYUN = "UPYUN";
    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String HEALTH_UP = "UP";
    public static final String HEALTH_DOWN = "DOWN";
    public static final String HEALTH_UNKNOWN = "UNKNOWN";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private String name;

    /** TELEGRAM | R2 | S3_COMPATIBLE | COS | OSS | QINIU | UPYUN */
    private String providerType;

    /** Telegram 目标频道/群 ID（TELEGRAM 必填） */
    private String chatId;

    /** Cloudflare Worker 公网地址，如 https://img.example.com（TELEGRAM 必填） */
    private String workerBaseUrl;

    /** Provider 专属扩展选项 JSON；TELEGRAM 非敏感，S3 系（R2/S3/七牛）含 accessKeyId/secretAccessKey，
     * COS 含 secretId/secretKey，OSS 含 accessKeyId/accessKeySecret，UPYUN 含 operator/password（接口返回时打码） */
    private String providerOptions;

    /** 单文件上限（字节）；空使用插件默认 */
    private Long maxFileSize;

    /** ENABLED | DISABLED */
    private String status;

    /** 默认配置标记（0/1，全表最多一个 1） */
    private Integer isDefault;

    /** UP | DOWN | UNKNOWN */
    private String healthStatus;

    private LocalDateTime lastTestTime;

    private String lastTestMessage;

    private String remark;

    private String createBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    private String updateBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
