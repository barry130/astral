package com.astral.system.notify;

import java.util.Map;

/**
 * 短信发送服务（系统级，SMS 渠道）
 *
 * <p>与邮件同构：插件发信授权（fail-closed，同一张 sys_mail_plugin_auth）→
 * 事件码解析短信模板（供应商模板映射）→ 每日额度（按手机号）→
 * 按权重选择启用的供应商 → SPI 发送 → 日志落库。</p>
 */
public interface SmsService {

    /**
     * 插件通道发送（走授权与额度）
     *
     * @param pluginId  调用方插件ID
     * @param phone     手机号
     * @param eventCode 事件码（sys_sms_template.event_code 解析供应商模板映射）
     * @param variables 模板变量（按名透传给供应商）
     */
    void send(String pluginId, String phone, String eventCode, Map<String, String> variables);

    /**
     * 系统内部告警短信（告警渠道用）：不走授权/额度。
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
