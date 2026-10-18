package com.astral.ai.service;

import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiProviderRouteEntity;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.ai.mapper.AiProviderMapper;
import com.astral.ai.mapper.AiProviderRouteMapper;
import com.astral.ai.provider.AiProviderDtos.ChatRequest;
import com.astral.ai.provider.AiProviderDtos.HealthResult;
import com.astral.ai.provider.AiProviderDtos.Message;
import com.astral.ai.provider.AiProviderRegistry;
import com.astral.ai.provider.MockAiProvider;
import com.astral.ai.security.AiSecretCipher;
import com.astral.ai.security.AiUrlGuard;
import com.astral.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提供方配置与连通性测试。
 *
 * <p>密钥永不出库：写入时加密或转存环境变量引用，读取时只在内存里解密交给
 * {@code AiProvider}，<b>任何 VO / 日志 / 事件都不带密钥</b>。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiProviderService {

    /** 提供方 URL 私有地址白名单配置键 */
    private static final String CONFIG_ALLOW_PRIVATE = "astral.plugins.ai.allow-private-hosts";

    private final AiProviderMapper providerMapper;
    private final AiModelMapper modelMapper;
    private final AiProviderRouteMapper routeMapper;
    private final AiProviderRegistry registry;
    private final AiSecretCipher cipher;

    /** 列出全部提供方 */
    public List<AiProviderEntity> list() {
        return providerMapper.selectList(null);
    }

    /** 取提供方，不存在抛错 */
    public AiProviderEntity require(Long id) {
        if (id == null) {
            throw new BusinessException("AI006", "未指定提供方");
        }
        AiProviderEntity entity = providerMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("AI006", "提供方不存在: " + id);
        }
        return entity;
    }

    /** 取启用中的提供方，禁用或不存在抛错 */
    public AiProviderEntity requireEnabled(Long id) {
        AiProviderEntity entity = require(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new BusinessException("AI006", "提供方已禁用: " + entity.getName());
        }
        return entity;
    }

    /** 新增提供方 */
    @Transactional(rollbackFor = Exception.class)
    public AiProviderEntity create(String providerCode, String name, String providerType, String baseUrl,
                                   String apiKey, String secretRef, Boolean enabled, Integer connectTimeoutMs,
                                   Integer readTimeoutMs, Integer maxRetries, Integer weight, String remark,
                                   Long operatorId) {
        Long exists = providerMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiProviderEntity>()
                .eq(AiProviderEntity::getProviderCode, providerCode));
        if (exists != null && exists > 0) {
            throw new BusinessException("AI006", "提供方编码已存在: " + providerCode);
        }
        registry.require(providerType);
        AiProviderEntity entity = new AiProviderEntity();
        entity.setProviderCode(providerCode);
        entity.setName(name);
        entity.setProviderType(providerType.toUpperCase(java.util.Locale.ROOT));
        entity.setBaseUrl(normalizeBaseUrl(entity.getProviderType(), baseUrl));
        entity.setEnabled(enabled == null || enabled);
        entity.setSecretRef(blankToNull(secretRef));
        entity.setEncryptedApiKey(apiKey == null || apiKey.isBlank() ? null : cipher.encrypt(apiKey.trim()));
        entity.setConnectTimeoutMs(connectTimeoutMs == null ? 15000 : connectTimeoutMs);
        entity.setReadTimeoutMs(readTimeoutMs == null ? 120000 : readTimeoutMs);
        entity.setMaxRetries(maxRetries == null ? 1 : maxRetries);
        entity.setWeight(weight == null ? 100 : weight);
        entity.setHealthStatus("UNKNOWN");
        entity.setRemark(remark);
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        providerMapper.insert(entity);
        return entity;
    }

    /** 更新提供方（apiKey 为空表示不改） */
    @Transactional(rollbackFor = Exception.class)
    public AiProviderEntity update(Long id, String name, String providerType, String baseUrl, String apiKey,
                                   String secretRef, Boolean enabled, Integer connectTimeoutMs, Integer readTimeoutMs,
                                   Integer maxRetries, Integer weight, String remark, Long operatorId) {
        AiProviderEntity entity = require(id);
        if (name != null) {
            entity.setName(name);
        }
        if (providerType != null && !providerType.isBlank()) {
            registry.require(providerType);
            entity.setProviderType(providerType.toUpperCase(java.util.Locale.ROOT));
        }
        if (baseUrl != null && !baseUrl.isBlank()) {
            entity.setBaseUrl(normalizeBaseUrl(entity.getProviderType(), baseUrl));
        }
        if (apiKey != null && !apiKey.isBlank()) {
            entity.setEncryptedApiKey(cipher.encrypt(apiKey.trim()));
            entity.setSecretRef(null);
        } else if (secretRef != null) {
            entity.setSecretRef(blankToNull(secretRef));
            if (entity.getSecretRef() != null) {
                entity.setEncryptedApiKey(null);
            }
        }
        if (enabled != null) {
            entity.setEnabled(enabled);
        }
        if (connectTimeoutMs != null) {
            entity.setConnectTimeoutMs(connectTimeoutMs);
        }
        if (readTimeoutMs != null) {
            entity.setReadTimeoutMs(readTimeoutMs);
        }
        if (maxRetries != null) {
            entity.setMaxRetries(maxRetries);
        }
        if (weight != null) {
            entity.setWeight(weight);
        }
        if (remark != null) {
            entity.setRemark(remark);
        }
        entity.setUpdatedBy(operatorId);
        providerMapper.updateById(entity);
        return entity;
    }

    /** 删除提供方（有模型或路由引用时拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiProviderEntity entity = require(id);
        Long models = modelMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiModelEntity>()
                .eq(AiModelEntity::getProviderId, id));
        if (models != null && models > 0) {
            throw new BusinessException("AI006", "提供方下仍有模型，无法删除: " + entity.getName());
        }
        Long routes = routeMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiProviderRouteEntity>()
                .eq(AiProviderRouteEntity::getProviderId, id));
        if (routes != null && routes > 0) {
            throw new BusinessException("AI006", "提供方仍被路由引用，无法删除: " + entity.getName());
        }
        providerMapper.deleteById(id);
    }

    /**
     * 连通性测试：用最小对话请求探测，结果回写 health_status。
     *
     * @param id     提供方
     * @param modelCode 探测模型（可空，取该提供方第一个启用模型）
     * @return 健康结果
     */
    public HealthResult test(Long id, String modelCode) {
        AiProviderEntity entity = require(id);
        String code = modelCode;
        if (code == null || code.isBlank()) {
            List<AiModelEntity> models = modelMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AiModelEntity>()
                            .eq(AiModelEntity::getProviderId, id)
                            .eq(AiModelEntity::getEnabled, true));
            if (models.isEmpty()) {
                HealthResult result = HealthResult.fail("该提供方尚未配置可用模型");
                markHealth(entity, result);
                return result;
            }
            code = models.get(0).getModelCode();
        }
        HealthResult result;
        try {
            ChatRequest request = new ChatRequest(entity.getBaseUrl(), resolveApiKey(entity), code,
                    List.of(Message.user("ping")), List.of(), 0.0, null, 16, null, null,
                    entity.getConnectTimeoutMs() == null ? 15000 : entity.getConnectTimeoutMs(),
                    entity.getReadTimeoutMs() == null ? 120000 : entity.getReadTimeoutMs(),
                    0, "health-" + id);
            result = registry.require(entity.getProviderType()).testConnection(request);
        } catch (Exception e) {
            log.warn("[AI] 提供方连通性测试失败 provider={} reason={}", entity.getProviderCode(), e.getMessage());
            result = HealthResult.fail(safeMessage(e));
        }
        markHealth(entity, result);
        return result;
    }

    /** 解析密钥（加密存储优先，其次环境变量引用）；都没有返回 null（部分本地模型不需要） */
    public String resolveApiKey(AiProviderEntity entity) {
        if (entity == null) {
            return null;
        }
        String encrypted = entity.getEncryptedApiKey();
        if (encrypted != null && !encrypted.isBlank()) {
            String plain = cipher.decrypt(encrypted);
            if (plain == null) {
                throw new BusinessException("AI006", "提供方密钥无法解密，请重新保存");
            }
            return plain;
        }
        String ref = entity.getSecretRef();
        if (ref != null && !ref.isBlank()) {
            String plain = cipher.resolveSecretRef(ref);
            if (plain == null || plain.isBlank()) {
                throw new BusinessException("AI006", "环境变量未配置: " + ref);
            }
            return plain;
        }
        return null;
    }

    /** 凭证来源（供后台展示，不泄露值） */
    public String credentialSource(AiProviderEntity entity) {
        return cipher.describe(entity.getEncryptedApiKey(), entity.getSecretRef());
    }

    /** 私有地址白名单（本地 Ollama 场景） */
    public String allowPrivateHosts() {
        return System.getProperty(CONFIG_ALLOW_PRIVATE, System.getenv("ASTRAL_AI_ALLOW_PRIVATE_HOSTS"));
    }

    private void markHealth(AiProviderEntity entity, HealthResult result) {
        AiProviderEntity patch = new AiProviderEntity();
        patch.setId(entity.getId());
        patch.setHealthStatus(result.healthy() ? "UP" : "DOWN");
        patch.setLastHealthTime(LocalDateTime.now());
        patch.setLastHealthMessage(AiJson.truncate(result.message(), 400));
        providerMapper.updateById(patch);
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return AiJson.truncate(message == null ? e.getClass().getSimpleName() : message, 400);
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /**
     * 规范化 base URL。
     *
     * <p>{@code MOCK} 是本地模拟实现，不发任何外部请求，因此不做 SSRF 校验，
     * 也允许留空（统一落成 {@code mock://local}，避免后台误填内网地址被当成真实出口）。
     * 其他类型一律过 {@link AiUrlGuard}。</p>
     */
    private String normalizeBaseUrl(String providerType, String baseUrl) {
        if (MockAiProvider.TYPE.equalsIgnoreCase(providerType)) {
            return baseUrl == null || baseUrl.isBlank() ? "mock://local" : baseUrl.trim();
        }
        return AiUrlGuard.validateBaseUrl(baseUrl, allowPrivateHosts());
    }
}
