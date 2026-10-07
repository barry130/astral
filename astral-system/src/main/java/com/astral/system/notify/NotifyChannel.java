package com.astral.system.notify;

import java.util.List;

/**
 * 通知渠道常量（消息中心渠道维度）
 *
 * <p>通知子系统按渠道演进：EMAIL（SMTP 账户池 + 本地渲染模板）、
 * SMS（供应商池 + 供应商侧模板映射与报备审核态）、INAPP（站内信）均已注册；
 * WEBHOOK 随各自渠道落地时再加入本注册表——加入后配套：渠道模板形态、
 * 渠道日志与配额、以及 {@code sys_mail_template.channel} 上的合法值。</p>
 *
 * <p>事件在 {@link NotifyEventDef#getChannels()} 里声明自己可投递的渠道；
 * 模板绑定校验（事件 × 渠道 → 模板）以此为准。</p>
 */
public final class NotifyChannel {

    /** 邮件（sys_mail_account SMTP 账户池 + sys_mail_template 本地渲染模板） */
    public static final String EMAIL = "EMAIL";

    /** 短信（sys_sms_provider 供应商池 + sys_sms_template 供应商模板映射，非本地渲染） */
    public static final String SMS = "SMS";

    /** 站内信（sys_notify_inapp 按用户落库；模板复用 sys_mail_template channel=INAPP 本地渲染） */
    public static final String INAPP = "INAPP";

    private NotifyChannel() {
    }

    /** 已注册的全部渠道（通知中心渠道下拉/规则绑定的数据源） */
    public static List<String> registered() {
        return List.of(EMAIL, SMS, INAPP);
    }

    /**
     * {@code sys_mail_template.channel} 的合法值（本地渲染型模板存放表）。
     * <p>SMS 模板是供应商侧映射，存 {@code sys_sms_template}，不进本表；
     * 站内信（INAPP）本地渲染，进本表。</p>
     */
    public static List<String> mailTemplateChannels() {
        return List.of(EMAIL, INAPP);
    }
}
