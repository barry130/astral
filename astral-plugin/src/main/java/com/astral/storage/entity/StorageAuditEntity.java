package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 存储审计（sys_storage_audit）
 * <p>不记录 Token、完整签名 URL、locator 或文件内容；只记录动作、主体、目标与结果。</p>
 */
@Data
@TableName("sys_storage_audit")
public class StorageAuditEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** CONFIG_CREATE / CONFIG_TEST / FOLDER_CREATE / FILE_UPLOAD / URL_ISSUE / FILE_DELETE ... */
    private String action;

    /** USER | ADMIN | PLUGIN | WORKER */
    private String subjectType;

    private String subjectId;

    /** CONFIG | FOLDER | FILE | TASK */
    private String targetType;

    private String targetId;

    private String detail;

    /** OK | DENIED | ERROR */
    private String result;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
