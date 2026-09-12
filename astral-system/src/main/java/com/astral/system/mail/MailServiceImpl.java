package com.astral.system.mail;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailAccount;
import com.astral.dao.entity.SysMailLog;
import com.astral.dao.entity.SysMailPluginAuth;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.mapper.SysMailAccountMapper;
import com.astral.dao.mapper.SysMailLogMapper;
import com.astral.dao.mapper.SysMailPluginAuthMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MailServiceImpl implements MailService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private SysMailAccountMapper accountMapper;
    @Resource
    private SysMailTemplateMapper templateMapper;
    @Resource
    private SysMailPluginAuthMapper pluginAuthMapper;
    @Resource
    private SysMailLogMapper logMapper;

    @Override
    public void send(String pluginId, String toEmail, String templateCode, Map<String, String> variables) {
        // 1. 插件发信授权校验
        SysMailPluginAuth auth = pluginAuthMapper.selectOne(
                new LambdaQueryWrapper<SysMailPluginAuth>().eq(SysMailPluginAuth::getPluginId, pluginId));
        if (auth == null || auth.getEnabled() == null || auth.getEnabled() != 1) {
            throw new BusinessException("MAIL003", pluginId);
        }
        // 2. 场景白名单校验
        if (auth.getAllowedScenes() != null && !auth.getAllowedScenes().isBlank()) {
            List<String> allowed = parseJsonArray(auth.getAllowedScenes());
            if (!allowed.contains(templateCode)) {
                throw new BusinessException("MAIL006", templateCode);
            }
        }
        // 3. 每日额度校验（按收件人 + 插件）
        Long sent = logMapper.countSuccessToday(toEmail, pluginId);
        int limit = auth.getDailyLimit() != null ? auth.getDailyLimit() : 2;
        if (sent != null && sent >= limit) {
            throw new BusinessException("MAIL004");
        }
        // 4. 模板校验
        SysMailTemplate tpl = templateMapper.selectOne(
                new LambdaQueryWrapper<SysMailTemplate>().eq(SysMailTemplate::getTemplateCode, templateCode));
        if (tpl == null) {
            throw new BusinessException("MAIL002", templateCode);
        }
        // 5. 渲染主题与正文
        String subject = render(tpl.getSubject(), variables);
        String content = render(tpl.getContent(), variables);
        // 6. 选择启用账户并发送（失败自动重试下一个）
        sendWithAccount(toEmail, subject, content, pluginId, templateCode);
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

    private void sendWithAccount(String toEmail, String subject, String content,
                                 String pluginId, String scene) {
        List<SysMailAccount> enabled = accountMapper.selectList(
                new LambdaQueryWrapper<SysMailAccount>().eq(SysMailAccount::getEnabled, 1));
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

    private String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (variables != null) {
            for (Map.Entry<String, String> e : variables.entrySet()) {
                result = result.replace("${" + e.getKey() + "}",
                        e.getValue() == null ? "" : e.getValue());
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

    private List<String> parseJsonArray(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Arrays.stream(json.replace("[", "").replace("]", "").replace("\"", "").split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
    }
}
