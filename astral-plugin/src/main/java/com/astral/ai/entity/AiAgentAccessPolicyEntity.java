package com.astral.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 智能体访问策略
 */
@Data
@TableName("ai_agent_access_policy")
public class AiAgentAccessPolicyEntity {

    /** 主键 */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 智能体 ID */
    private Long agentId;

    /** 主体类型 */
    private String subjectType;

    /** 主体 ID */
    private Long subjectId;

    /** 是否允许访问 */
    private Boolean allowAccess;

    /** 每日请求上限 */
    private Integer dailyRequestLimit;

    /** 每月请求上限 */
    private Integer monthlyRequestLimit;

    /** 备注 */
    private String remark;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
