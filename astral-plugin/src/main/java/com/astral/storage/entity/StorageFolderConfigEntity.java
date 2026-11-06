package com.astral.storage.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 文件夹-存储配置绑定（sys_storage_folder_config）
 *
 * <p>一个文件夹可绑定多个存储配置，priority 决定 FAILOVER 的故障转移顺序
 * （小值优先）；ROUND_ROBIN 时同优先级内轮询。绑定为空 = 该文件夹禁止上传。</p>
 */
@Data
@TableName("sys_storage_folder_config")
public class StorageFolderConfigEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    private Long folderId;

    private Long storageConfigId;

    /** 优先级（0–10000，小值优先，默认 100） */
    private Integer priority;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
