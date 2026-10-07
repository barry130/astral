package com.astral.system.notify;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailPluginAuth;
import com.astral.dao.entity.SysSmsLog;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysMailPluginAuthMapper;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.notify.sms.SmsProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 短信发送实现：链路与 {@code MailServiceImpl} 同构（授权 fail-closed → 模板解析 →
 * 占额 → 供应商池发送 → 落日志/回补），额度 Redis key 独立（astral:sms:quota:*）。
 * 供应商模板语义：本地不渲染正文，变量按名透传，内容留档存参数 JSON。
 */
@Slf4j
@Service
public class SmsServiceImpl implements SmsService {

    private static final int DEFAULT_DAILY_LIMIT = 2;
    private static final String QUOTA_KEY_PREFIX = "astral:sms:quota:";

    private final Map<String, SmsProvider> providerImpls;

    private final SysSmsProviderMapper providerMapper;
    private final SysSmsTemplateMapper templateMapper;
    private final SysSmsLogMapper logMapper;
    private final SysMailPluginAuthMapper pluginAuthMapper;

    @Autowired(required = false)
    private StringRedisTemplate stringRedisTemplate;

    /** 模板缓存（key=事件码）与供应商池缓存（单键），60s，写路径主动失效 */
    private final Cache<String, SysSmsTemplate> templateCache = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(Duration.ofSeconds(60)).build();
    private static final String KEY_PROVIDERS = "enabled";
    private final Cache<String, List<SysSmsProvider>> providerCache = Caffeine.newBuilder()
            .maximumSize(4).expireAfterWrite(Duration.ofSeconds(60)).build();

    public SmsServiceImpl(List<SmsProvider> impls,
                          SysSmsProviderMapper providerMapper,
                          SysSmsTemplateMapper templateMapper,
                          SysSmsLogMapper logMapper,
                          SysMailPluginAuthMapper pluginAuthMapper) {
        this.providerImpls = impls.stream().collect(java.util.stream.Collectors
                .toUnmodifiableMap(SmsProvider::type, p -> p));
        this.providerMapper = providerMapper;
        this.templateMapper = templateMapper;
        this.logMapper = logMapper;
        this.pluginAuthMapper = pluginAuthMapper;
    }

    private boolean tryConsumeQuota(String pluginId, String phone, int limit) {
        if (stringRedisTemplate == null) {
            Long sent = logMapper.countSuccessToday(phone, pluginId);
            return sent == null || sent < limit;
        }
        String key = QUOTA_KEY_PREFIX + LocalDate.now() + ":" + pluginId + ":" + phone;
        try {
            Long used = stringRedisTemplate.opsForValue().increment(key);
            if (used == null) {
                return true;
            }
            if (used == 1L) {
                long ttl = Math.max(LocalDateTime.now().until(LocalDate.now().plusDays(1).atStartOfDay(),
                        ChronoUnit.SECONDS), 1L);
                stringRedisTemplate.expire(key, ttl, TimeUnit.SECONDS);
            }
            if (used > limit) {
                stringRedisTemplate.opsForValue().decrement(key);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[Sms] 额度计数失败，降级为数据库计数: {}", e.getMessage());
            Long sent = logMapper.countSuccessToday(phone, pluginId);
            return sent == null || sent < limit;
        }
    }

    private void refundQuota(String pluginId, String phone) {
        // 降级路径（DB 计数）只统计成功记录，无需回补；Redis 计数偏高是保守方向，允许极小误差
        if (stringRedisTemplate == null) {
            return;
        }
        String key = QUOTA_KEY_PREFIX + LocalDate.now() + ":" + pluginId + ":" + phone;
        try {
            Long used = stringRedisTemplate.opsForValue().get(key) == null
                    ? null : Long.valueOf(stringRedisTemplate.opsForValue().get(key));
            if (used != null && used > 0) {
                stringRedisTemplate.opsForValue().decrement(key);
            }
        } catch (Exception e) {
            log.warn("[Sms] 额度回补失败: {}", e.getMessage());
        }
    }

    @Override
    public void send(String pluginId, String phone, String eventCode, Map<String, String> variables) {
        // 1. 授权校验（与邮件同一张 sys_mail_plugin_auth，fail-closed）
        SysMailPluginAuth auth = pluginAuthMapper.selectOne(
                new LambdaQueryWrapper<SysMailPluginAuth>().eq(SysMailPluginAuth::getPluginId, pluginId));
        if (auth == null || auth.getEnabled() == null || auth.getEnabled() != 1) {
            throw new BusinessException("MAIL003", pluginId);
        }
        String allowedRaw = auth.getAllowedScenes();
        if (allowedRaw == null || allowedRaw.isBlank()) {
            throw new BusinessException("MAIL018", pluginId);
        }
        if (!NotifyEventRegistry.parseList(allowedRaw).contains(eventCode)) {
            throw new BusinessException("MAIL006", eventCode);
        }
        // 2. 模板解析
        SysSmsTemplate tpl = templateCache.get(eventCode, k -> templateMapper.selectOne(
                new LambdaQueryWrapper<SysSmsTemplate>().eq(SysSmsTemplate::getEventCode, k)));
        if (tpl == null) {
            throw new BusinessException("SMS002", eventCode);
        }
        // 3. 每日额度（按手机号）
        int limit = auth.getDailyLimit() != null && auth.getDailyLimit() > 0 ? auth.getDailyLimit() : DEFAULT_DAILY_LIMIT;
        if (!tryConsumeQuota(pluginId, phone, limit)) {
            throw new BusinessException("SMS005");
        }
        // 4. 发送（失败回补额度）
        try {
            sendWithProvider(phone, tpl, variables, pluginId, eventCode);
        } catch (RuntimeException e) {
            refundQuota(pluginId, phone);
            throw e;
        }
    }

    @Override
    public void sendSystemAlert(String phone, String title, String content) {
        SysSmsTemplate tpl = templateCache.get(NotifyEventRegistry.SYSTEM_ALERT, k -> templateMapper.selectOne(
                new LambdaQueryWrapper<SysSmsTemplate>().eq(SysSmsTemplate::getEventCode, k)));
        if (tpl == null) {
            throw new IllegalStateException("短信模板未配置（事件 " + NotifyEventRegistry.SYSTEM_ALERT + "）");
        }
        sendWithProvider(phone, tpl, Map.of("title", title == null ? "" : title,
                "content", content == null ? "" : content), "system", NotifyEventRegistry.SYSTEM_ALERT);
    }

    @Override
    public void testSendTemplate(Long templateId, Long providerId, String phone, Map<String, String> variables) {
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("SMS004");
        }
        SysSmsTemplate tpl = templateMapper.selectById(templateId);
        if (tpl == null) {
            throw new BusinessException("SMS002", templateId);
        }
        if (providerId != null) {
            SysSmsProvider provider = providerMapper.selectById(providerId);
            if (provider == null || provider.getEnabled() == null || provider.getEnabled() != 1) {
                throw new BusinessException("SMS001");
            }
            try {
                doSend(provider, phone, tpl, variables);
                saveLog(provider.getId(), "system", tpl.getEventCode(), phone, variables, 1, null);
            } catch (Exception e) {
                saveLog(provider.getId(), "system", tpl.getEventCode(), phone, variables, 0, e.getMessage());
                throw new BusinessException("SMS003", e.getMessage());
            }
            return;
        }
        sendWithProvider(phone, tpl, variables, "system", tpl.getEventCode());
    }

    /** 按权重在启用供应商池中选择并发送，失败自动换下一个，全失败抛 SMS003 */
    private void sendWithProvider(String phone, SysSmsTemplate tpl, Map<String, String> variables,
                                  String pluginId, String eventCode) {
        List<SysSmsProvider> enabled = providerCache.get(KEY_PROVIDERS, k -> providerMapper.selectList(
                new LambdaQueryWrapper<SysSmsProvider>().eq(SysSmsProvider::getEnabled, 1)));
        if (enabled == null || enabled.isEmpty()) {
            throw new BusinessException("SMS001");
        }
        List<SysSmsProvider> pool = new ArrayList<>();
        for (SysSmsProvider p : enabled) {
            int w = p.getWeight() != null && p.getWeight() > 0 ? p.getWeight() : 1;
            for (int i = 0; i < w; i++) {
                pool.add(p);
            }
        }
        java.util.Collections.shuffle(pool);
        List<SysSmsProvider> ordered = new ArrayList<>(new LinkedHashSet<>(pool));

        Exception lastError = null;
        for (SysSmsProvider provider : ordered) {
            try {
                doSend(provider, phone, tpl, variables);
                saveLog(provider.getId(), pluginId, eventCode, phone, variables, 1, null);
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("[Sms] 供应商[{}]发送失败，尝试下一个: {}", provider.getProviderName(), e.getMessage());
            }
        }
        saveLog(null, pluginId, eventCode, phone, variables, 0,
                lastError != null ? lastError.getMessage() : "未知错误");
        throw new BusinessException("SMS003", lastError != null ? lastError.getMessage() : "");
    }

    private void doSend(SysSmsProvider provider, String phone, SysSmsTemplate tpl, Map<String, String> variables) throws Exception {
        SmsProvider impl = providerImpls.get(provider.getProviderType() == null ? "" : provider.getProviderType().toUpperCase());
        if (impl == null) {
            throw new BusinessException("SMS006", provider.getProviderType());
        }
        // 签名：模板级覆盖优先，回退供应商级
        String signName = tpl.getSignName() != null && !tpl.getSignName().isBlank()
                ? tpl.getSignName() : provider.getSignName();
        impl.send(provider, phone, signName, tpl.getProviderTemplateCode(), variables);
    }

    private void saveLog(Long providerId, String pluginId, String scene, String phone,
                         Map<String, String> variables, int status, String errorMsg) {
        try {
            SysSmsLog logRow = new SysSmsLog();
            logRow.setProviderId(providerId);
            logRow.setPluginId(pluginId);
            logRow.setScene(scene);
            logRow.setPhone(phone);
            logRow.setContent(String.valueOf(variables));
            logRow.setStatus(status);
            logRow.setErrorMsg(errorMsg);
            logRow.setSendTime(LocalDateTime.now());
            logMapper.insert(logRow);
        } catch (Exception e) {
            log.error("[Sms] 发送记录写入失败", e);
        }
    }

    @Override
    public void evictProviderCache() {
        providerCache.invalidateAll();
    }

    @Override
    public void evictTemplateCache() {
        templateCache.invalidateAll();
    }
}
