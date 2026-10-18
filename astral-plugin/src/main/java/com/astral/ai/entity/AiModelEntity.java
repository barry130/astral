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
 * AI 模型
 */
@Data
@TableName("ai_model")
public class AiModelEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 所属提供方 ID */
    private Long providerId;

    /** 模型编码 */
    private String modelCode;

    /** 模型名称 */
    private String modelName;

    /** 模型类型 */
    private String modelKind;

    /** 是否支持流式 */
    private Boolean supportsStream;

    /** 是否支持工具调用 */
    private Boolean supportsTools;

    /** 是否支持 JSON 输出 */
    private Boolean supportsJson;

    /** 是否支持图片输入 */
    private Boolean supportsVision;

    /** 最大上下文 Token */
    private Integer maxContextTokens;

    /** 最大输出 Token */
    private Integer maxOutputTokens;

    /** 输入单价（每千 Token） */
    private BigDecimal inputPrice;

    /** 输出单价（每千 Token） */
    private BigDecimal outputPrice;

    /** 币种 */
    private String currency;

    /** 是否启用 */
    private Boolean enabled;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
