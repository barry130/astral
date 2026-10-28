package com.astral.system.mail;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailAccount;
import com.astral.dao.entity.SysMailLog;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.mapper.SysMailAccountMapper;
import com.astral.dao.mapper.SysMailLogMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.system.notify.NotifyChannel;
import com.astral.system.notify.NotifyEventRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Slf4j
@Service
public class MailServiceImpl implements MailService {

    @Resource
    private SysMailAccountMapper accountMapper;
    @Resource
    private SysMailTemplateMapper templateMapper;
    @Resource
    private SysMailLogMapper logMapper;

    // ==================== 进程内只读缓存（TTL 60s，管理端写路径主动失效） ====================
    // 每次发信原先要打 3 轮 sys_mail_* 表（模板/启用账户；插件授权那一轮随授权表删除一并消失），
    // 验证码等高频场景全部缓存；缓存条目只读不写，MailAccount/MailTemplate 控制器的写操作调用
    // evict* 立即失效。

    /** 启用账户缓存键（单键） */
    private static final String KEY_ENABLED_ACCOUNTS = "enabled";

    /** 邮件模板（key=templateCode） */
    private final Cache<String, SysMailTemplate> templateCache = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /** 启用中的发信账户（单键） */
    private final Cache<String, List<SysMailAccount>> enabledAccountsCache = Caffeine.newBuilder()
            .maximumSize(4)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    @Override
    public void sendSystemAlert(String toEmail, String subject, String textBody) {
        // 告警邮件模板化：命中 systemAlert 场景绑定的模板则按 ${title}/${content} 渲染，
        // 未绑定模板时回退内置样式直发（告警是系统自身触发的低频事件，不查授权、不占额度）。
        SysMailTemplate tpl = templateCache.get(NotifyEventRegistry.SYSTEM_ALERT, this::resolveTemplate);
        if (tpl != null) {
            Map<String, String> vars = Map.of("title", subject == null ? "" : subject,
                    "content", textBody == null ? "" : textBody);
            String renderedSubject = render(tpl.getSubject(), vars);
            String content = renderAdmin(tpl.getContent(), vars);
            sendWithAccount(toEmail,
                    renderedSubject == null || renderedSubject.isBlank() ? subject : renderedSubject,
                    content, "system", NotifyEventRegistry.SYSTEM_ALERT);
            return;
        }
        String html = "<div style=\"font-family:sans-serif;padding:24px;white-space:pre-wrap;\">"
                + textBody.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</div>";
        // pluginId=system、scene=alert：只影响 sys_mail_log 里的归属标记，不走授权/额度
        sendWithAccount(toEmail, subject, html, "system", NotifyEventRegistry.SYSTEM_ALERT);
    }

    /**
     * 模板解析：参数是事件码，优先按 {@code sys_mail_template.scene} × EMAIL 渠道绑定解析
     * （scene+channel 唯一索引保证至多一条，orderBy 兜底防御），未命中再按 {@code template_code}
     * 直查——兼容只建了模板、没填 scene 的存量数据与老调用方。
     */
    private SysMailTemplate resolveTemplate(String sceneOrCode) {
        List<SysMailTemplate> byScene = templateMapper.selectList(
                new LambdaQueryWrapper<SysMailTemplate>()
                        .eq(SysMailTemplate::getScene, sceneOrCode)
                        .eq(SysMailTemplate::getChannel, NotifyChannel.EMAIL)
                        .orderByDesc(SysMailTemplate::getUpdateTime)
                        .last("LIMIT 1"));
        if (!byScene.isEmpty()) {
            return byScene.get(0);
        }
        return templateMapper.selectOne(new LambdaQueryWrapper<SysMailTemplate>()
                .eq(SysMailTemplate::getTemplateCode, sceneOrCode));
    }

    /** 失效启用账户缓存：MailAccountController 的 create/update/delete/toggle 后调用 */
    @Override
    public void evictAccountCache() {
        enabledAccountsCache.invalidateAll();
    }

    /** 失效模板缓存：MailTemplateController 的 create/update/delete 后调用 */
    @Override
    public void evictTemplateCache() {
        templateCache.invalidateAll();
    }

    @Override
    public void testSend(Long accountId, String toEmail) {
        SysMailAccount acc = accountMapper.selectById(accountId);
        if (acc == null || acc.getEnabled() == null || acc.getEnabled() != 1) {
            throw new BusinessException("MAIL001");
        }
        String subject = "轻听音乐 - 邮箱配置测试";
        String content = "<div style=\"font-family:sans-serif;padding:24px;\"><h3>邮箱配置测试</h3>"
                + "<p>这是一封测试邮件，发送时间：" + LocalDateTime.now() + "</p>"
                + "<p>若您收到此邮件，说明该邮箱账户配置正确。</p></div>";
        try {
            doSend(acc, toEmail, subject, content);
            saveLog(acc.getId(), "system", "test", toEmail, subject, content, 1, null);
        } catch (Exception e) {
            saveLog(acc.getId(), "system", "test", toEmail, subject, content, 0, e.getMessage());
            throw new BusinessException("MAIL005", e.getMessage());
        }
    }

    @Override
    public void testSendTemplate(Long templateId, Long accountId, String toEmail, Map<String, String> variables) {
        if (toEmail == null || toEmail.isBlank()) {
            throw new BusinessException("MAIL014");
        }
        SysMailTemplate tpl = templateMapper.selectById(templateId);
        if (tpl == null) {
            throw new BusinessException("MAIL002", templateId);
        }
        // 管理端试发与预览同语义：变量值由管理员显式提供，不做 HTML 转义（如原样输出链接）
        String subject = renderAdmin(tpl.getSubject(), variables);
        String content = renderAdmin(tpl.getContent(), variables);
        String scene = tpl.getScene() != null && !tpl.getScene().isBlank() ? tpl.getScene() : "test";
        if (accountId != null) {
            SysMailAccount acc = accountMapper.selectById(accountId);
            if (acc == null || acc.getEnabled() == null || acc.getEnabled() != 1) {
                throw new BusinessException("MAIL001");
            }
            try {
                doSend(acc, toEmail, subject, content);
                saveLog(acc.getId(), "system", scene, toEmail, subject, content, 1, null);
            } catch (Exception e) {
                saveLog(acc.getId(), "system", scene, toEmail, subject, content, 0, e.getMessage());
                throw new BusinessException("MAIL005", e.getMessage());
            }
            return;
        }
        sendWithAccount(toEmail, subject, content, "system", scene);
    }

    private void sendWithAccount(String toEmail, String subject, String content,
                                 String pluginId, String scene) {
        List<SysMailAccount> enabled = enabledAccountsCache.get(KEY_ENABLED_ACCOUNTS, k -> accountMapper.selectList(
                new LambdaQueryWrapper<SysMailAccount>().eq(SysMailAccount::getEnabled, 1)));
        if (enabled == null || enabled.isEmpty()) {
            throw new BusinessException("MAIL001");
        }
        // 按权重展开后乱序去重，保证权重高的账户更可能被选中，且失败时不重复同一账户
        List<SysMailAccount> pool = new ArrayList<>();
        for (SysMailAccount acc : enabled) {
            int w = acc.getWeight() != null && acc.getWeight() > 0 ? acc.getWeight() : 1;
            for (int i = 0; i < w; i++) {
                pool.add(acc);
            }
        }
        java.util.Collections.shuffle(pool);
        List<SysMailAccount> ordered = new ArrayList<>(new LinkedHashSet<>(pool));

        Exception lastError = null;
        for (SysMailAccount acc : ordered) {
            try {
                doSend(acc, toEmail, subject, content);
                saveLog(acc.getId(), pluginId, scene, toEmail, subject, content, 1, null);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("[Mail] 账户[{}]发送失败，尝试下一个: {}", acc.getAccountName(), e.getMessage());
            }
        }
        saveLog(null, pluginId, scene, toEmail, subject, content, 0,
                lastError != null ? lastError.getMessage() : "未知错误");
        throw new BusinessException("MAIL005", lastError != null ? lastError.getMessage() : "");
    }

    private void doSend(SysMailAccount acc, String toEmail, String subject, String content) throws Exception {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(acc.getSmtpHost());
        sender.setPort(acc.getSmtpPort());
        sender.setUsername(acc.getUsername());
        sender.setPassword(acc.getPassword());

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.connectiontimeout", "10000");
        if (acc.getSslEnable() != null && acc.getSslEnable() == 1) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }
        sender.setJavaMailProperties(props);

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        String fromName = (acc.getFromName() != null && !acc.getFromName().isBlank())
                ? acc.getFromName() : acc.getFromAddr();
        helper.setFrom(acc.getFromAddr(), fromName);
        helper.setTo(toEmail);
        helper.setSubject(subject);
        helper.setText(content, true);
        sender.send(message);
        log.info("[Mail] 邮件已发送: to={}, account={}", toEmail, acc.getAccountName());
    }

    /**
     * 渲染模板：把 {@code ${key}} 替换为变量值
     *
     * <p><b>必须对变量值做 HTML 转义</b>：模板正文是以 {@code text/html} 发出的
     * （见 {@code helper.setText(content, true)}），变量里若带 HTML/脚本会被收件人邮箱直接渲染。
     * 典型攻击面：用户昵称、反馈标题这类用户可控内容被塞进验证码/通知邮件，
     * 攻击者可注入 {@code <img src=x onerror=...>} 或钓鱼链接做品牌仿冒。
     * 转义只影响显示，不影响纯文本语义。</p>
     */
    private String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                result = result.replace("${" + e.getKey() + "}", htmlEscape(e.getValue()));
            }
        }
        return result;
    }

    /**
     * HTML 转义（&amp; &lt; &gt; &quot; &#39;）
     *
     * @param value 原始值，可为 null
     * @return 转义后的安全字符串；null 转为空串
     */
    private static String htmlEscape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 管理端渲染（预览/试发用）：与 {@link #render} 同规则但不做 HTML 转义——
     * 变量值由管理员显式提供，需要原样输出（如正文里的链接）。
     */
    private String renderAdmin(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                result = result.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return result;
    }

    private void saveLog(Long accountId, String pluginId, String scene, String toEmail,
                         String subject, String content, int status, String errorMsg) {
        try {
            SysMailLog log = new SysMailLog();
            log.setAccountId(accountId);
            log.setPluginId(pluginId);
            log.setScene(scene);
            log.setToEmail(toEmail);
            log.setSubject(subject);
            log.setContent(content);
            log.setStatus(status);
            log.setErrorMsg(errorMsg);
            log.setSendTime(LocalDateTime.now());
            logMapper.insert(log);
        } catch (Exception e) {
            log.error("[Mail] 发送记录写入失败", e);
        }
    }
}
