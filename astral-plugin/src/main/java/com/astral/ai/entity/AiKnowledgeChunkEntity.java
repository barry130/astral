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
 * AI 知识分片
 */
@Data
@TableName("ai_knowledge_chunk")
public class AiKnowledgeChunkEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文档 ID */
    private Long documentId;

    /** 知识源 ID */
    private Long sourceId;

    /** 分片序号 */
    private Integer chunkNo;

    /** 章节 */
    private String section;

    /** 正文 */
    private String content;

    /** Token 数量 */
    private Integer tokenCount;

    /** 向量 */
    private String embeddingJson;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
