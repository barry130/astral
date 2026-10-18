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
 * AI 智能体
 */
@Data
@TableName("ai_agent")
public class AiAgentEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 智能体编码 */
    private String agentCode;

    /** 智能体名称 */
    private String name;

    /** 智能体类型 */
    private String agentType;

    /** 描述 */
    private String description;

    /** 图标 */
    private String icon;

    /** 状态 */
    private String status;

    /** 可用客户端 */
    private String clientTypes;

    /** 入口配置 */
    private String entryConfig;

    /** 使用权限编码 */
    private String permissionCode;

    /** 各客户端权限编码 */
    private String clientPermissionCodes;

    /** 额度策略 ID */
    private Long quotaPolicyId;

    /** 当前发布版本 ID */
    private Long publishedVersionId;

    /** 允许用户上下文 */
    private Boolean allowUserContext;

    /** 允许文件上下文 */
    private Boolean allowFileContext;

    /** 允许会话 */
    private Boolean conversationEnabled;

    /** 允许反馈 */
    private Boolean feedbackEnabled;

    /** 排序 */
    private Integer sort;

    /** 创建人 */
    private Long createdBy;

    /** 更新人 */
    private Long updatedBy;

    /** 删除时间 */
    private LocalDateTime deletedAt;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
