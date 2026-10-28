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
 * @param code             事件码（全局唯一，调用方传入的标识）
 * @param name             展示名（管理端用）
 * @param description      事件说明（何时触发、谁在调用）
 * @param payloadFields    触发时携带的上下文字段（模板可用的 ${占位符} 全集）
 * @param channels         该事件可投递的渠道（见 {@link NotifyChannel}）
 * @param noticeType       站内信行的类型标签回退值（sys_notice.notice_type，取值只有
 *                         {@code announce}(公告) / {@code notice}(通知) 两类）。INAPP 投递时先取 payload 里的
 *                         meta 字段 {@code noticeType}（调用方逐条指定），缺省再用本值，
 *                         再缺省 {@code announce}；仅事件类型固定时有意义，可传 null
 * @param requiredChannels 该事件<b>必须</b>保留的渠道：无论管理端如何配置规则，保存规则时这些渠道的
 *                         行都会被强制启用（目前只有「找回密码验证码」这类安全相关事件要求 EMAIL 必达，
 *                         避免运营误删规则后用户彻底收不到验证码）。可为空
 * @param sender           触发方标识（{@code system} / {@code qt} / {@code ai} / {@code feedback}），
 *                         INAPP 投递时原样写入 {@code sys_notice.sender}，供消息中心按来源归类与消息统计
 *                         分组。为什么必须在这里显式声明、而不是从事件码前缀猜：事件码只是字符串约定，
 *                         猜前缀会把「ai 发的站内信」错记成反馈插件来源，来源归属只能由发送方自己说
 */
public record NotifyEventDef(
        String code,
        String name,
        String description,
        List<NotifyEventField> payloadFields,
        List<String> channels,
        String noticeType,
        List<String> requiredChannels,
        String sender) {

    /** 兼容旧签名（无必达渠道、未声明触发方） */
    public NotifyEventDef(String code, String name, String description,
                          List<NotifyEventField> payloadFields, List<String> channels, String noticeType) {
        this(code, name, description, payloadFields, channels, noticeType, List.of(), null);
    }

    /** 兼容旧签名（有必达渠道、未声明触发方） */
    public NotifyEventDef(String code, String name, String description,
                          List<NotifyEventField> payloadFields, List<String> channels, String noticeType,
                          List<String> requiredChannels) {
        this(code, name, description, payloadFields, channels, noticeType, requiredChannels, null);
    }

    /** payload 字段名列表（模板 variables 校验用） */
    public List<String> payloadFieldNames() {
        return payloadFields.stream().map(NotifyEventField::name).toList();
    }

    /** 是否存在必达渠道约束 */
    public boolean hasRequiredChannel() {
        return requiredChannels != null && !requiredChannels.isEmpty();
    }
}
