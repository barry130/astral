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
     * @param sceneCode    场景码（优先按 sys_mail_template.scene 绑定解析模板，
     *                     未命中回退按 template_code 直查，兼容未填 scene 的存量模板）
     * @param variables    模板变量（如 code=123456）
     */
    void send(String pluginId, String toEmail, String sceneCode, Map<String, String> variables);

    /**
     * 系统内部告警邮件直发（告警渠道用）
     *
     * <p>不走插件授权/每日额度：告警由服务端自身触发、量小且有冷却，
     * 授权链路反而会让「告警发不出去」成为常态。若 {@code sys_mail_template} 中存在
     * scene={@link MailSceneRegistry#SYSTEM_ALERT} 绑定的模板，按 ${title}/${content}
     * 渲染发送（后台可改文案）；否则回退内置样式直发。</p>
     *
     * @param toEmail 收件邮箱（告警渠道配置里指定）
     * @param subject 邮件主题（渲染为模板变量 title）
     * @param textBody 纯文本正文（渲染为模板变量 content）
     */
    void sendSystemAlert(String toEmail, String subject, String textBody);

    /** 后台测试发送：使用指定账户向指定邮箱发送一封测试邮件 */
    void testSend(Long accountId, String toEmail);

    /**
     * 模板试发（管理端）：按模板渲染变量后向指定邮箱真实发送一封，走发信账户与日志落库，
     * 不校验插件授权、不占每日额度（与账户测试发送同语义）。
     *
     * @param templateId 模板ID
     * @param accountId  发信账户ID；null 时按权重在启用账户中自动选择
     * @param toEmail    收件邮箱（必填）
     * @param variables  模板变量值（管理端提供，渲染不做 HTML 转义，与预览同语义）
     */
    void testSendTemplate(Long templateId, Long accountId, String toEmail, Map<String, String> variables);

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
