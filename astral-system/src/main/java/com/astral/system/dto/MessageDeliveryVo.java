package com.astral.system.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * 单条投递明细（三渠道合并行）。
 *
 * <p>字段是按「消息」这个统一概念对齐后的映射，不是某张表的原样投影：</p>
 * <ul>
 *   <li>{@code recipient}：EMAIL = {@code to_email}、SMS = {@code phone}、
 *       INAPP = {@code user_id}（广播行为空时用 {@code all} 标记）；</li>
 *   <li>{@code title}：EMAIL = {@code subject}、SMS = 正文截断（短信没有标题列）、
 *       INAPP = {@code title} 优先、为空时正文截断；</li>
 *   <li>{@code sendTime}：EMAIL/SMS = {@code send_time}、INAPP = {@code create_time}；</li>
 *   <li>{@code pluginId}：EMAIL/SMS = {@code plugin_id}、INAPP = {@code feedback}。</li>
 * </ul>
 */
@Data
public class MessageDeliveryVo {

    /** 源表主键（跨渠道不唯一，仅用于前端 key） */
    private Long id;

    /** 投递渠道：EMAIL / SMS / INAPP */
    private String channel;

    /** 场景（事件码），库里为空时归为 unknown */
    private String scene;

    /** 收件人：邮箱 / 手机号 / 用户 ID / all（INAPP 广播） */
    private String recipient;

    /** 标题：邮件主题，或短信/站内信正文的截断摘要 */
    private String title;

    /** 1 成功 / 0 失败（INAPP 恒为 1） */
    private Integer status;

    /** 状态的展示文案：成功 / 失败 */
    private String statusText;

    /** 失败原因（成功时为空） */
    private String errorMsg;

    /** 发送时间（各渠道口径见类注释） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sendTime;

    /** 发送方插件 */
    private String pluginId;
}
