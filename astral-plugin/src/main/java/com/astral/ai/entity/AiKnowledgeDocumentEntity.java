package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * AI 知识文档
 */
@Data
@TableName("ai_knowledge_document")
public class AiKnowledgeDocumentEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 知识源 ID */
    private Long sourceId;

    /** 文档键 */
    private String docKey;

    /** 标题 */
    private String title;

    /** 路径 */
    private String path;

    /** 章节 */
    private String section;

    /** 版本 */
    private String version;

    /** 正文 */
    private String content;

    /** 内容摘要 */
    private String contentHash;

    /** 状态 */
    private String status;

    /** 分片数量 */
    private Integer chunkCount;

    /** 索引时间 */
    private LocalDateTime indexedAt;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
