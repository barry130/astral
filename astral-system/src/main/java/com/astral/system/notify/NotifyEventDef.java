package com.astral.system.notify;

import java.util.List;

/**
 * 通知事件定义
 *
 * <p>事件是「系统里会发生的一类消息」的代码注册契约（见 {@link NotifyEventRegistry}）：
 * 触发时机由业务代码决定，事件码就是调用方与通知子系统之间的那条契约线。
 * 模板通过 {@code sys_mail_template.scene}（存事件码）× {@code channel} 绑定到事件，
 * 授权通过 {@code allowed_scenes} 圈定插件可发的事件范围。</p>
 *
 * @param code          事件码（全局唯一，调用方传入的标识）
 * @param name          展示名（管理端用）
 * @param description   事件说明（何时触发、谁在调用）
 * @param payloadFields 触发时携带的上下文字段（模板可用的 ${占位符} 全集）
 * @param channels      该事件可投递的渠道（见 {@link NotifyChannel}）
 * @param noticeType    站内信行的类型标签回退值（sys_notice.notice_type：
 *                      announce/feedback/request）。INAPP 投递时先取 payload 里的
 *                      meta 字段 {@code noticeType}（调用方逐条指定），缺省再用本值，
 *                      再缺省 {@code announce}；仅事件类型固定时有意义，可传 null
 */
public record NotifyEventDef(
        String code,
        String name,
        String description,
        List<NotifyEventField> payloadFields,
        List<String> channels,
        String noticeType) {

    /** payload 字段名列表（模板 variables 校验用） */
    public List<String> payloadFieldNames() {
        return payloadFields.stream().map(NotifyEventField::name).toList();
    }
}
