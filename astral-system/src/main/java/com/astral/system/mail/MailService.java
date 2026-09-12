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

    /** 后台测试发送：使用指定账户向指定邮箱发送一封测试邮件 */
    void testSend(Long accountId, String toEmail);
}
