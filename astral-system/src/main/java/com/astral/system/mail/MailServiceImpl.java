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
import com.astral.system.notify.NotifyChannel;
import com.astral.system.notify.NotifyEventRegistry;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

import java.time.Duration;
import jakarta.mail.internet.MimeMessage;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
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

    /** 默认每日额度（授权记录未配置 daily_limit 或配置为 0/负数时使用） */
    private static final int DEFAULT_DAILY_LIMIT = 2;

    /** 发信额度 Redis key 前缀 */
    private static final String QUOTA_KEY_PREFIX = "astral:mail:quota:";

    /**
     * 原子回补额度：仅当计数 &gt; 0 时 DECR（key 不存在/已耗尽时不动作，避免凭空造出 -1 计数器），
     * 且 TTL 缺失（-1，永不过期）时补上当日过期；ARGV[1] = 到次日 0 点的秒数。
     */
    private static final DefaultRedisScript<Long> REFUND_QUOTA_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]) "
                    + "if (not v) or (tonumber(v) <= 0) then return -1 end "
                    + "local n = redis.call('DECR', KEYS[1]) "
                    + "if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return n",
            Long.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Resource
    private SysMailAccountMapper accountMapper;
    @Resource
    private SysMailTemplateMapper templateMapper;
    @Resource
    private SysMailPluginAuthMapper pluginAuthMapper;
    @Resource
    private SysMailLogMapper logMapper;

    /**
     * Redis（可选）
     * <p>额度计数用 Redis 原子自增；Redis 不可用时降级为数据库计数（弱一致，仅兜底），
     * 不让基础设施故障直接导致业务不可用。</p>
     */
    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    // ==================== 进程内只读缓存（TTL 60s，管理端写路径主动失效） ====================
    // 每次发信原先要打 3 轮 sys_mail_* 表（插件授权/模板/启用账户），验证码等高频场景全部缓存；
    // 缓存条目只读不写，MailAccount/MailTemplate/MailPluginAuth 控制器的写操作调用 evict* 立即失效。

    /** 启用账户缓存键（单键） */
    private static final String KEY_ENABLED_ACCOUNTS = "enabled";

    /** 插件发信授权（key=pluginId） */
    private final Cache<String, SysMailPluginAuth> pluginAuthCache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

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

    /**
     * 原子占用一次发信额度
     *
     * @param pluginId 插件ID
     * @param toEmail  收件人
     * @param limit    每日上限
     * @return 占用成功返回 true；已达上限返回 false
     */
    private boolean tryConsumeQuota(String pluginId, String toEmail, int limit) {
        if (stringRedisTemplate == null) {
            // 降级：无 Redis 时退回数据库计数（并发下可能超额，但不会出现不可用）
            log.warn("[Mail] Redis 不可用，每日额度降级为数据库计数，并发下可能超额");
            Long sent = logMapper.countSuccessToday(toEmail, pluginId);
            return sent == null || sent < limit;
        }
        String key = QUOTA_KEY_PREFIX + LocalDate.now() + ":" + pluginId + ":" + toEmail;
        try {
            Long used = stringRedisTemplate.opsForValue().increment(key);
            if (used == null) {
                return true;
            }
            if (used == 1L) {
                // 首次计数：设置到当日 24 点过期，避免计数器常驻
                stringRedisTemplate.expire(key, secondsUntilTomorrow(), TimeUnit.SECONDS);
            }
            if (used > limit) {
                // 超限时把这次自增退回去，保持计数准确（并发下有极小误差，不影响限流语义）
                stringRedisTemplate.opsForValue().decrement(key);
                return false;
            }
            return true;
        } catch (Exception e) {
            // Redis 抖动不应让发信整体不可用，退回数据库计数
            log.warn("[Mail] 额度计数失败，降级为数据库计数: {}", e.getMessage());
            Long sent = logMapper.countSuccessToday(toEmail, pluginId);
            return sent == null || sent < limit;
        }
    }

    /** 当前时刻距次日 0 点的秒数（最少 1 秒，避免 expire 0 导致 key 不落盘） */
    private long secondsUntilTomorrow() {
        long sec = LocalDateTime.now().until(LocalDate.now().plusDays(1).atStartOfDay(), ChronoUnit.SECONDS);
        return Math.max(sec, 1L);
    }

    /**
     * 回补一次发信额度（占额度之后的发送环节失败时调用）。
     * <p>{@link #tryConsumeQuota} 是「先占后发」，而发送可能因为无可用账户、SMTP 报错等原因失败。
     * 不回补就会把用户当天的正常配额无谓消耗掉（表现为「验证码额度莫名用完」）。
     * 回补本身失败只记日志：限流语义下计数偏高是保守方向，不影响可用性。</p>
     *
     * <p>必须用 Lua 原子回补而不是裸 {@code DECR}：额度可能是在 Redis 抖动期间按
     * 「数据库计数」降级路径占用的（Redis 里根本没有这个 key）。此时裸 DECR 会让 Redis
     * 凭空创建一个值为 {@code -1} 且无 TTL 的计数器——它当日永不过期，且起点为 -1，
     * 等于当天比限额多发一封；与「{@code used==1} 才设置过期」的窗口交错时同样会留下
     * 无 TTL 的常驻 key。Lua 里只在计数 &gt; 0 时回补，并在 TTL 缺失时补上当日过期。</p>
     *
     * @param pluginId 插件ID
     * @param toEmail  收件人
     */
    private void refundQuota(String pluginId, String toEmail) {
        if (stringRedisTemplate == null) {
            // 降级模式下额度来自 DB 统计（只统计成功记录），无需也不应回补
            return;
        }
        String key = QUOTA_KEY_PREFIX + LocalDate.now() + ":" + pluginId + ":" + toEmail;
        try {
            stringRedisTemplate.execute(REFUND_QUOTA_SCRIPT,
                    List.of(key), String.valueOf(secondsUntilTomorrow()));
        } catch (Exception e) {
            log.warn("[Mail] 额度回补失败: {}", e.getMessage());
        }
    }

    @Override
    public void send(String pluginId, String toEmail, String sceneCode, Map<String, String> variables) {
        // 1. 插件发信授权校验（缓存 60s，插件授权写操作主动失效）
        SysMailPluginAuth auth = pluginAuthCache.get(pluginId, k -> pluginAuthMapper.selectOne(
                new LambdaQueryWrapper<SysMailPluginAuth>().eq(SysMailPluginAuth::getPluginId, k)));
        if (auth == null || auth.getEnabled() == null || auth.getEnabled() != 1) {
            throw new BusinessException("MAIL003", pluginId);
        }
        // 2. 场景白名单校验（fail-closed）：授权未配置 allowed_scenes = 一律拒绝。
        //    空白语义从「全部允许」反转为「全部拒绝」——授权的价值在于明示范围，缺省不猜；
        //    插件侧不再自带硬编码白名单，能发什么完全由这张表决定。
        String allowedRaw = auth.getAllowedScenes();
        if (allowedRaw == null || allowedRaw.isBlank()) {
            throw new BusinessException("MAIL018", pluginId);
        }
        List<String> allowed = parseJsonArray(allowedRaw);
        if (!allowed.contains(sceneCode)) {
            throw new BusinessException("MAIL006", sceneCode);
        }
        // 3. 模板解析（缓存 60s）：参数是场景码，优先按 scene 绑定解析，未命中回退 template_code
        //    必须先于「占额度」：模板不存在属于参数错误，不该白扣一次发信配额。
        SysMailTemplate tpl = templateCache.get(sceneCode, this::resolveTemplate);
        if (tpl == null) {
            throw new BusinessException("MAIL002", sceneCode);
        }
        // 4. 渲染主题与正文（渲染失败同样发生在占额度之前，不会消耗配额）
        String subject = render(tpl.getSubject(), variables);
        String content = render(tpl.getContent(), variables);
        // 5. 每日额度校验（按收件人 + 插件）
        //    原实现是「先 COUNT 成功记录，再发送」：两次操作之间有窗口，
        //    并发请求可以同时读到未超限的计数，导致实际发送量远超 daily_limit（验证码轰炸）。
        //    这里改为 Redis 原子占位（INCR + 当日过期），超限即拒绝。
        int limit = auth.getDailyLimit() != null && auth.getDailyLimit() > 0 ? auth.getDailyLimit() : DEFAULT_DAILY_LIMIT;
        if (!tryConsumeQuota(pluginId, toEmail, limit)) {
            throw new BusinessException("MAIL004");
        }
        // 6. 选择启用账户并发送（失败自动重试下一个）
        //    发送失败要回补额度，否则「无可用账户 / SMTP 全部失败」会把用户当天的验证码配额吃掉。
        try {
            sendWithAccount(toEmail, subject, content, pluginId, sceneCode);
        } catch (RuntimeException e) {
            refundQuota(pluginId, toEmail);
            throw e;
        }
    }

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

    /** 失效插件授权缓存：MailPluginAuthController 的 create/update/delete 后调用 */
    @Override
    public void evictPluginAuthCache() {
        pluginAuthCache.invalidateAll();
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
