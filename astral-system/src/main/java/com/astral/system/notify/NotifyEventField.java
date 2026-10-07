package com.astral.system.notify;

/**
 * 通知事件的 payload 字段声明
 *
 * <p>事件发布方在触发时刻会携带一组上下文数据（如验证码 code、告警标题 title）。
 * 这里逐字段声明名字与含义，供三处消费：模板编辑页提示可用的 {@code ${占位符}}、
 * 模板保存校验（variables 必须覆盖全部 payload 字段）、以及后续事件规则页的
 * 收件人字段下拉。</p>
 *
 * @param name        字段名（即模板里的 ${占位符} 名）
 * @param description 字段含义与格式说明（如「6 位数字验证码，有效期 10 分钟」）
 */
public record NotifyEventField(String name, String description) {
}
