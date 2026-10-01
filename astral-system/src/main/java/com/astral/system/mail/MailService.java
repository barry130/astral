package com.astral.system.mail;

import java.util.Map;

/**
 * 邮件发送服务（系统级）
 * <p>插件通过 {@link #send(String, String, String, Map)} 调用，由本服务完成：
 * 插件发信授权校验、每日额度控制、按权重随机选择启用的邮箱账户、
 * 模板变量渲染、失败自动重试、发送记录落库。</p>
 */
public interface MailService {

    /**
     * 发送邮件
     *
     * @param pluginId     调用方插件ID（如 qt），用于授权与额度控制
     * @param toEmail      收件邮箱
     * @param templateCode 模板编码（场景标识）
     * @param variables    模板变量（如 code=123456）
     */
    void send(String pluginId, String toEmail, String templateCode, Map<String, String> variables);

    /**
     * 系统内部告警邮件直发（告警渠道用）
     *
     * <p>不走插件授权/模板/每日额度：告警由服务端自身触发、量小且有冷却，
     * 授权链路反而会让「告警发不出去」成为常态。正文为纯文本（按 
 换行）。</p>
     *
     * @param toEmail 收件邮箱（告警渠道配置里指定）
     * @param subject 邮件主题
     * @param textBody 纯文本正文
     */
    void sendSystemAlert(String toEmail, String subject, String textBody);

    /** 后台测试发送：使用指定账户向指定邮箱发送一封测试邮件 */
    void testSend(Long accountId, String toEmail);

    // ==================== 缓存失效（管理端写路径调用） ====================
    // 发送路径的授权/模板/启用账户读取走 60s 进程内缓存（见 MailServiceImpl），
    // 对应管理端 CRUD 完成后必须调用以下方法立即失效，避免最长 60s 的配置延迟。

    /** 失效启用账户缓存：账户 新增/更新/删除/启停 后调用 */
    void evictAccountCache();

    /** 失效模板缓存：模板 新增/更新/删除 后调用 */
    void evictTemplateCache();

    /** 失效插件授权缓存：插件授权 新增/更新/删除 后调用 */
    void evictPluginAuthCache();
}
