package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存储任务（sys_storage_task）——远端删除补偿等异步动作
 */
@Data
@TableName("sys_storage_task")
public class StorageTaskEntity {

    public static final String TYPE_DELETE_REMOTE = "DELETE_REMOTE_OBJECT";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEAD = "DEAD";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** DELETE_REMOTE_OBJECT */
    private String taskType;

    private Long fileId;

    /** 任务参数 JSON（Telegram: chatId/messageId/publicId） */
    private String payloadJson;

    private Integer retryCount;

    private LocalDateTime nextRetryTime;

    /** PENDING | RUNNING | SUCCESS | FAILED | DEAD */
    private String status;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
