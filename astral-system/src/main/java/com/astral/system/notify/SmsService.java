package com.astral.system.notify;

import java.util.Map;

/**
 * 短信发送服务（系统级，SMS 渠道）
 *
 * <p>与邮件同构：事件码解析短信模板（供应商模板映射）→
 * 按权重选择启用的供应商 → SPI 发送 → 日志落库。
 * 限额由通知规则渠道行上的 daily_limit 承担；旧的「插件发信授权 + 按手机号每日额度」
 * 随插件发信授权表下线删除（那套闸门全仓已无调用方，留着只会误以为还在生效）。</p>
 */
public interface SmsService {

    /**
     * 系统内部告警短信（告警渠道用）：不走通知规则限额。
     * 命中 systemAlert 事件的短信模板则发送，未绑定模板抛异常（由告警记录 FAIL）。
     */
    void sendSystemAlert(String phone, String title, String content);

    /** 管理端模板试发：指定供应商（null 自动按权重选择），真实发送一条 */
    void testSendTemplate(Long templateId, Long providerId, String phone, Map<String, String> variables);

    /** 失效供应商缓存：供应商 新增/更新/删除/启停 后调用 */
    void evictProviderCache();

    /** 失效模板缓存：短信模板 新增/更新/删除 后调用 */
    void evictTemplateCache();
}
