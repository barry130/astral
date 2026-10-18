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
 * AI 提供方路由
 */
@Data
@TableName("ai_provider_route")
public class AiProviderRouteEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 路由编码 */
    private String routeCode;

    /** 路由名称 */
    private String name;

    /** 提供方 ID */
    private Long providerId;

    /** 模型 ID */
    private Long modelId;

    /** 兜底模型 ID */
    private Long fallbackModelId;

    /** 温度 */
    private BigDecimal temperature;

    /** Top P */
    private BigDecimal topP;

    /** 最大输出 Token */
    private Integer maxTokens;

    /** 额外模型参数 */
    private String extraParametersJson;

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
