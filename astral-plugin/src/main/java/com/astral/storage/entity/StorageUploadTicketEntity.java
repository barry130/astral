package com.astral.storage.entity;

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
@TableName("sys_storage_upload_ticket")
public class StorageUploadTicketEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("upload_id")
    @Size(max = 64)
    @NotNull
    private String uploadId;

    @TableField("folder_id")
    @NotNull
    private Long folderId;

    @TableField("actor_id")
    @Size(max = 64)
    private String actorId;

    @TableField("payload_json")
    @NotNull
    private String payloadJson;

    @TableField("receipt_json")
    private String receiptJson;

    @TableField("expires_at")
    @NotNull
    private LocalDateTime expiresAt;

    @Size(max = 24)
    @NotNull
    private String state;

    @TableField("file_id")
    private Long fileId;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(value = "update_time", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
