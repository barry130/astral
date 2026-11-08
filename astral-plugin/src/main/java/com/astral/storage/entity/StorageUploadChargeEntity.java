package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("sys_storage_upload_charge")
public class StorageUploadChargeEntity {

    @TableId(type = IdType.INPUT)
    private Long id;

    @TableField("upload_id")
    @Size(max = 64)
    @NotNull
    private String uploadId;

    @TableField("file_id")
    @NotNull
    private Long fileId;

    @TableField("folder_id")
    @NotNull
    private Long folderId;

    @TableField("actor_id")
    @Size(max = 64)
    private String actorId;

    @TableField("quota_day")
    @NotNull
    private LocalDate quotaDay;

    @NotNull
    private Boolean charge;

    @TableField(value = "create_time", fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
