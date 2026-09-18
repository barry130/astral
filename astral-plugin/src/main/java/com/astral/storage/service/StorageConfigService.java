package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 存储配置服务（多 Provider：TELEGRAM Worker 直连 / R2 / S3 兼容 / 腾讯 COS / 阿里 OSS / 七牛 S3 网关 / 又拍云）
 * <p>Bot Token 保存在 Cloudflare Worker Secret，本服务只管理非敏感连接信息；
 * 对象存储系凭证保存在 provider_options（接口返回时打码）。</p>
 */
@Slf4j
@Service
public class StorageConfigService {

    private static final String TABLE = "sys_storage_config";

    @Resource
    private StorageConfigMapper configMapper;

    @Resource
    private StorageFileMapper fileMapper;

    @Resource
    private StorageAuditService auditService;

    @Resource
    private S3ObjectService s3ObjectService;

    @Resource
    private CosApiService cosApiService;

    @Resource
    private OssApiService ossApiService;

    @Resource
    private UpyunApiService upyunApiService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<StorageConfigEntity> listAll() {
        List<StorageConfigEntity> list = configMapper.selectList(new LambdaQueryWrapper<StorageConfigEntity>()
                .orderByDesc(StorageConfigEntity::getIsDefault)
                .orderByAsc(StorageConfigEntity::getId));
        list.forEach(this::redactSecret);
        return list;
    }

    public Page<StorageConfigEntity> page(long current, long size) {
        Page<StorageConfigEntity> result = configMapper.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<StorageConfigEntity>()
                        .orderByDesc(StorageConfigEntity::getIsDefault)
                        .orderByAsc(StorageConfigEntity::getId));
        result.getRecords().forEach(this::redactSecret);
        return result;
    }

    public StorageConfigEntity getById(Long id) {
        StorageConfigEntity config = configMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("STORAGE002");
        }
        return config;
    }

    /** 解析默认启用的存储配置（STORAGE004） */
    public StorageConfigEntity requireDefaultEnabled() {
        StorageConfigEntity config = configMapper.selectOne(new LambdaQueryWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getIsDefault, 1)
                .eq(StorageConfigEntity::getStatus, StorageConfigEntity.STATUS_ENABLED)
                .last("LIMIT 1"));
        if (config == null) {
            throw new BusinessException("STORAGE004");
        }
        return config;
    }

    public StorageConfigEntity create(StorageDtos.ConfigCreateReq req, String operator) {
        if (req.name() == null || req.name().isBlank() || req.name().length() > 128) {
            throw new BusinessException("COMMON002", "配置名称不能为空且不超过 128 字符");
        }
        String providerType = normalizeProvider(req.providerType());
        Long exists = configMapper.selectCount(new LambdaQueryWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getName, req.name().trim()));
        if (exists != null && exists > 0) {
            throw new BusinessException("COMMON002", "配置名称已存在");
        }
        String optionsJson = validateAndNormalizeOptions(providerType, req.providerOptions(), null);
        StorageConfigEntity config = new StorageConfigEntity();
        config.setName(req.name().trim());
        config.setProviderType(providerType);
        config.setChatId(req.chatId());
        config.setWorkerBaseUrl(req.workerBaseUrl());
        config.setProviderOptions(optionsJson);
        config.setMaxFileSize(req.maxFileSize());
        config.setRemark(req.remark());
        config.setStatus(StorageConfigEntity.STATUS_ENABLED);
        // 首个配置自动设为默认
        Long total = configMapper.selectCount(null);
        config.setIsDefault(total == null || total == 0 ? 1 : 0);
        config.setHealthStatus(StorageConfigEntity.HEALTH_UNKNOWN);
        config.setCreateBy(operator);
        config.setUpdateBy(operator);
        configMapper.insert(config);
        auditService.record("CONFIG_CREATE", "USER", operator, "CONFIG", String.valueOf(config.getId()),
                "name=" + config.getName() + " provider=" + providerType, "OK");
        return redactSecret(config);
    }

    public StorageConfigEntity update(Long id, StorageDtos.ConfigUpdateReq req, String operator) {
        StorageConfigEntity config = getById(id);
        String providerType = normalizeProvider(config.getProviderType());
        String optionsJson = validateAndNormalizeOptions(providerType, req.providerOptions(), config);
        if (StorageConfigEntity.PROVIDER_TELEGRAM.equals(providerType)) {
            validateTelegram(req.chatId(), req.workerBaseUrl());
        }
        config.setChatId(req.chatId());
        config.setWorkerBaseUrl(req.workerBaseUrl());
        config.setProviderOptions(optionsJson);
        config.setMaxFileSize(req.maxFileSize());
        config.setRemark(req.remark());
        if (req.status() != null && !req.status().isBlank()) {
            if (!StorageConfigEntity.STATUS_ENABLED.equals(req.status())
                    && !StorageConfigEntity.STATUS_DISABLED.equals(req.status())) {
                throw new BusinessException("COMMON002", "非法的状态值");
            }
            config.setStatus(req.status());
        }
        config.setUpdateBy(operator);
        config.setHealthStatus(StorageConfigEntity.HEALTH_UNKNOWN);
        configMapper.updateById(config);
        auditService.record("CONFIG_UPDATE", "USER", operator, "CONFIG", String.valueOf(id), null, "OK");
        return redactSecret(config);
    }

    public void delete(Long id, String operator) {
        StorageConfigEntity config = getById(id);
        Long fileCount = fileMapper.selectCount(new LambdaQueryWrapper<com.astral.storage.entity.StorageFileEntity>()
                .eq(com.astral.storage.entity.StorageFileEntity::getStorageConfigId, id));
        if (fileCount != null && fileCount > 0) {
            throw new BusinessException("COMMON002", "该配置仍被 " + fileCount + " 个文件引用，无法删除");
        }
        configMapper.deleteById(id);
        auditService.record("CONFIG_DELETE", "USER", operator, "CONFIG", String.valueOf(id),
                "name=" + config.getName(), "OK");
    }

    /** 设为默认配置（全表唯一默认） */
    public void setDefault(Long id, String operator) {
        getById(id);
        configMapper.update(null, new LambdaUpdateWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getIsDefault, 1)
                .set(StorageConfigEntity::getIsDefault, 0));
        configMapper.update(null, new LambdaUpdateWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getId, id)
                .set(StorageConfigEntity::getIsDefault, 1));
        auditService.record("CONFIG_SET_DEFAULT", "USER", operator, "CONFIG", String.valueOf(id), null, "OK");
    }

    /**
     * 连接测试：TELEGRAM 请求 Worker /healthz（内部 getMe）；
     * S3 系/COS/OSS/UPYUN 由各适配服务执行桶级只读请求。
     * 只发出小体积请求，不涉及文件内容。
     */
    public StorageDtos.ConfigTestResp test(Long id, String operator) {
        StorageConfigEntity config = getById(id);
        String providerType = normalizeProvider(config.getProviderType());
        String message;
        String botUsername = null;
        String health;
        if (StorageConfigEntity.PROVIDER_TELEGRAM.equals(providerType)) {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
                HttpRequest request = HttpRequest.newBuilder(URI.create(stripTrailingSlash(config.getWorkerBaseUrl()) + "/healthz"))
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + response.statusCode());
                }
                JsonNode node = objectMapper.readTree(response.body());
                if (!node.path("ok").asBoolean(false)) {
                    throw new IllegalStateException("Worker 健康检查返回异常");
                }
                botUsername = node.path("bot").asText(null);
                health = StorageConfigEntity.HEALTH_UP;
                message = "连接正常" + (botUsername != null ? "（Bot: @" + botUsername + "）" : "");
            } catch (Exception e) {
                health = StorageConfigEntity.HEALTH_DOWN;
                message = "连接失败: " + rootMessage(e);
                log.warn("[Storage] 配置连接测试失败: id={}", id, e);
            }
        } else {
            try {
                headBucketByProvider(providerType, config);
                health = StorageConfigEntity.HEALTH_UP;
                message = "连接正常（provider: " + providerType + "）";
            } catch (BusinessException e) {
                health = StorageConfigEntity.HEALTH_DOWN;
                message = "连接失败: " + e.getMessage();
            } catch (Exception e) {
                health = StorageConfigEntity.HEALTH_DOWN;
                message = "连接失败: " + rootMessage(e);
                log.warn("[Storage] 存储连接测试失败: id={}, provider={}", id, providerType, e);
            }
        }
        config.setHealthStatus(health);
        config.setLastTestTime(LocalDateTime.now());
        config.setLastTestMessage(message);
        config.setUpdateBy(operator);
        configMapper.updateById(config);
        auditService.record("CONFIG_TEST", "USER", operator, "CONFIG", String.valueOf(id), message, health);
        return new StorageDtos.ConfigTestResp(health, message, botUsername);
    }

    /** 按 Provider 分派桶级只读连接测试 */
    private void headBucketByProvider(String providerType, StorageConfigEntity config) {
        if (ProviderSupport.isS3Family(providerType)) {
            s3ObjectService.headBucket(config);
        } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
            cosApiService.headBucket(config);
        } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
            ossApiService.headBucket(config);
        } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            upyunApiService.headBucket(config);
        } else {
            throw new BusinessException("COMMON002", "不支持的存储类型: " + providerType);
        }
    }

    /** 归一化 Provider 类型（缺省 TELEGRAM，非法值拒绝） */
    public String normalizeProvider(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return StorageConfigEntity.PROVIDER_TELEGRAM;
        }
        String type = providerType.trim().toUpperCase();
        if (!ProviderSupport.isKnown(type)) {
            throw new BusinessException("COMMON002", "不支持的存储类型: " + providerType);
        }
        return type;
    }

    /**
     * 校验并归一化 provider_options：TELEGRAM 清空；
     * S3 系（R2/S3/七牛）校验 S3 必填项，COS/OSS/UPYUN 校验各自必填项；
     * 密钥字段（secretAccessKey/secretKey/accessKeySecret/password）为空或打码值时沿用已存值。
     */
    private String validateAndNormalizeOptions(String providerType, java.util.Map<String, String> options,
                                               StorageConfigEntity existing) {
        if (StorageConfigEntity.PROVIDER_TELEGRAM.equals(providerType)) {
            return null;
        }
        java.util.Map<String, String> merged = new java.util.HashMap<>();
        if (existing != null && existing.getProviderOptions() != null && !existing.getProviderOptions().isBlank()) {
            try {
                JsonNode old = objectMapper.readTree(existing.getProviderOptions());
                old.fieldNames().forEachRemaining(n -> merged.put(n, old.path(n).asText(null)));
            } catch (Exception ignored) {
            }
        }
        if (options != null) {
            options.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    merged.put(k, v.trim());
                }
            });
        }
        // 各 Provider 的密钥字段：空/打码时沿用已存值（新建时必须提供）
        String secretField = secretFieldOf(providerType);
        String secret = secretField == null ? "" : merged.get(secretField);
        if (secretField != null && (secret == null || secret.isBlank() || "******".equals(secret))) {
            if (existing == null) {
                throw new BusinessException("STORAGE024");
            }
            String oldSecret = oldOption(existing, secretField);
            if (oldSecret == null || oldSecret.isBlank()) {
                throw new BusinessException("STORAGE024");
            }
            merged.put(secretField, oldSecret);
        }
        // UPYUN 的 tokenKey 是可选密钥：打码或留空时沿用已存值（未配置则保持未配置）
        if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            String tokenKey = merged.get("tokenKey");
            if ("******".equals(tokenKey)) {
                String oldTokenKey = oldOption(existing, "tokenKey");
                if (oldTokenKey != null && !oldTokenKey.isBlank()) {
                    merged.put("tokenKey", oldTokenKey);
                } else {
                    merged.remove("tokenKey");
                }
            }
        }
        StorageConfigEntity probe = new StorageConfigEntity();
        probe.setProviderType(providerType);
        try {
            probe.setProviderOptions(objectMapper.writeValueAsString(merged));
        } catch (Exception e) {
            throw new BusinessException("COMMON002", "provider_options 序列化失败");
        }
        optionsProbe(providerType, probe); // 触发必填校验（STORAGE024/COMMON002）
        return probe.getProviderOptions();
    }

    /** Provider → 密钥字段名（无密钥返回 null） */
    private String secretFieldOf(String providerType) {
        if (ProviderSupport.isS3Family(providerType)) {
            return "secretAccessKey";
        }
        if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
            return "secretKey";
        }
        if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
            return "accessKeySecret";
        }
        if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            return "password";
        }
        return null;
    }

    /** 按 Provider 调用对应适配服务校验 provider_options 必填项 */
    private void optionsProbe(String providerType, StorageConfigEntity probe) {
        if (ProviderSupport.isS3Family(providerType)) {
            s3ObjectService.optionsOf(probe);
        } else if (StorageConfigEntity.PROVIDER_COS.equals(providerType)) {
            cosApiService.optionsOf(probe);
        } else if (StorageConfigEntity.PROVIDER_OSS.equals(providerType)) {
            ossApiService.optionsOf(probe);
        } else if (StorageConfigEntity.PROVIDER_UPYUN.equals(providerType)) {
            upyunApiService.optionsOf(probe);
        }
    }

    private String oldOption(StorageConfigEntity existing, String field) {
        try {
            return objectMapper.readTree(existing.getProviderOptions()).path(field).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** 接口出参打码：密钥字段永不回传前端（secretAccessKey/secretKey/accessKeySecret/password） */
    private StorageConfigEntity redactSecret(StorageConfigEntity config) {
        if (config.getProviderOptions() != null && !config.getProviderOptions().isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(config.getProviderOptions());
                boolean changed = false;
                for (String field : new String[]{"secretAccessKey", "secretKey", "accessKeySecret", "password", "tokenKey"}) {
                    if (node.hasNonNull(field)) {
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put(field, "******");
                        changed = true;
                    }
                }
                if (changed) {
                    config.setProviderOptions(objectMapper.writeValueAsString(node));
                }
            } catch (Exception ignored) {
            }
        }
        return config;
    }

    private void validateTelegram(String chatId, String workerBaseUrl) {
        if (chatId == null || chatId.isBlank() || !chatId.trim().matches("^-?\\d+$|^@\\w{4,64}$")) {
            throw new BusinessException("COMMON002", "Chat ID 必须为数字频道/群 ID 或 @频道名");
        }
        if (workerBaseUrl == null || !workerBaseUrl.trim().matches("^https?://[\\w.-]+(:\\d+)?$")) {
            throw new BusinessException("COMMON002", "Worker 地址必须为合法的 http(s) 地址");
        }
    }

    private String rootMessage(Exception e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
