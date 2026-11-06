package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * AI 工具
 */
@Data
@TableName("ai_tool")
public class AiToolEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 工具编码 */
    private String toolCode;

    /** 工具名称 */
    private String name;

    /** 描述 */
    private String description;

    /** 处理器键（代码登记；声明式工具固定为 declarative:http 等） */
    private String handlerKey;

    /** 执行器类型：CODE=代码注册（默认） / HTTP=声明式 HTTP 调用 */
    private String executorType;

    /** 声明式执行配置 JSON（executor_type 非 CODE 时使用） */
    private String executorConfig;

    /** 可用客户端 */
    private String clientTypes;

    /** 所需权限 */
    private String permissionCodes;

    /** 风险等级 */
    private String riskLevel;

    /** 确认策略 */
    private String approvalPolicy;

    /** 参数结构 */
    private String argumentsSchema;

    /** 结果结构 */
    private String resultSchema;

    /** 副作用说明 */
    private String sideEffects;

    /** 状态 */
    private String status;

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
