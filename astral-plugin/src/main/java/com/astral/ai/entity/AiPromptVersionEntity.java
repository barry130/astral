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
 * AI 提示词版本
 */
@Data
@TableName("ai_prompt_version")
public class AiPromptVersionEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 模板 ID */
    private Long templateId;

    /** 版本号 */
    private Integer versionNo;

    /** 版本状态 */
    private String status;

    /** 系统提示词 */
    private String systemPrompt;

    /** 用户提示词模板 */
    private String userPromptTemplate;

    /** 变量定义 */
    private String variablesJson;

    /** 变更说明 */
    private String changelog;

    /** 发布人 */
    private Long publishedBy;

    /** 发布时间 */
    private LocalDateTime publishedAt;

    /** 创建人 */
    private Long createdBy;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
