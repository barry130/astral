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
 * AI 提供方
 */
@Data
@TableName("ai_provider")
public class AiProviderEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 提供方编码 */
    private String providerCode;

    /** 提供方名称 */
    private String name;

    /** 提供方类型 */
    private String providerType;

    /** 接口基础地址 */
    private String baseUrl;

    /** 是否启用 */
    private Boolean enabled;

    /** 密钥引用（环境变量名） */
    private String secretRef;

    /** 加密后的 API Key */
    private String encryptedApiKey;

    /** 连接超时（毫秒） */
    private Integer connectTimeoutMs;

    /** 读取超时（毫秒） */
    private Integer readTimeoutMs;

    /** 最大重试次数 */
    private Integer maxRetries;

    /** 负载权重 */
    private Integer weight;

    /** 健康状态 */
    private String healthStatus;

    /** 最近健康检查时间 */
    private LocalDateTime lastHealthTime;

    /** 最近健康检查说明 */
    private String lastHealthMessage;

    /** 备注 */
    private String remark;

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
