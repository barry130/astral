package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 知识索引任务
 */
@Data
@TableName("ai_knowledge_index_job")
public class AiKnowledgeIndexJobEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 知识源 ID */
    private Long sourceId;

    /** 任务类型 */
    private String jobType;

    /** 任务状态 */
    private String status;

    /** 总数 */
    private Integer totalCount;

    /** 已处理 */
    private Integer processedCount;

    /** 失败数 */
    private Integer failedCount;

    /** 开始时间 */
    private LocalDateTime startedAt;

    /** 结束时间 */
    private LocalDateTime finishedAt;

    /** 错误摘要 */
    private String errorSummary;

    /** 创建人 */
    private Long createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
