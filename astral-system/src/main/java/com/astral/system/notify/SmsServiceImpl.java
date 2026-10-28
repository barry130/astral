package com.astral.system.notify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysSmsLog;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.notify.sms.SmsProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 短信发送实现：模板解析 → 供应商池发送 → 落日志。
 * 供应商模板语义：本地不渲染正文，变量按名透传，内容留档存参数 JSON。
 *
 * <p>限额由通知规则渠道行上的 daily_limit 承担；原「插件授权 + 按手机号每日额度」随
 * 插件发信授权表下线删除（唯一入口 {@code send(pluginId,...)} 全仓无调用方）。</p>
 */
@Slf4j
@Service
public class SmsServiceImpl implements SmsService {

    private final Map<String, SmsProvider> providerImpls;

    private final SysSmsProviderMapper providerMapper;
    private final SysSmsTemplateMapper templateMapper;
    private final SysSmsLogMapper logMapper;

    /** 模板缓存（key=事件码）与供应商池缓存（单键），60s，写路径主动失效 */
    private final Cache<String, SysSmsTemplate> templateCache = Caffeine.newBuilder()
            .maximumSize(128).expireAfterWrite(Duration.ofSeconds(60)).build();
    private static final String KEY_PROVIDERS = "enabled";
    private final Cache<String, List<SysSmsProvider>> providerCache = Caffeine.newBuilder()
            .maximumSize(4).expireAfterWrite(Duration.ofSeconds(60)).build();

    public SmsServiceImpl(List<SmsProvider> impls,
                          SysSmsProviderMapper providerMapper,
                          SysSmsTemplateMapper templateMapper,
                          SysSmsLogMapper logMapper) {
        this.providerImpls = impls.stream().collect(java.util.stream.Collectors
                .toUnmodifiableMap(SmsProvider::type, p -> p));
        this.providerMapper = providerMapper;
        this.templateMapper = templateMapper;
        this.logMapper = logMapper;
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
