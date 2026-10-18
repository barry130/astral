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
 * AI 知识源
 */
@Data
@TableName("ai_knowledge_source")
public class AiKnowledgeSourceEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 知识源编码 */
    private String sourceCode;

    /** 知识源名称 */
    private String name;

    /** 知识源类型 */
    private String sourceType;

    /** 描述 */
    private String description;

    /** 路径匹配 */
    private String pathPattern;

    /** 是否启用 */
    private Boolean enabled;

    /** 同步方式 */
    private String syncMode;

    /** 状态 */
    private String status;

    /** 最近同步时间 */
    private LocalDateTime lastSyncTime;

    /** 文档数量 */
    private Integer documentCount;

    /** 创建人 */
    private Long createdBy;

    /** 更新人 */
    private Long updatedBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
