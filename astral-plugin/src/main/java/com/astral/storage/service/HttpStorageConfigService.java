package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.dto.HttpChannelDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderConfigEntity;
import com.astral.storage.entity.StoragePermissionConfigEntity;
import com.astral.storage.http.ApprovedTargetPolicy;
import com.astral.storage.http.HttpChannelTestTransport;
import com.astral.storage.http.HttpProfileValidator;
import com.astral.storage.http.HttpSecretEnvelope;
import com.astral.storage.http.HttpTemplateEngine;
import com.astral.storage.http.HttpUploadProfile;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderConfigMapper;
import com.astral.storage.mapper.StoragePermissionConfigMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * CUSTOM_HTTP 渠道配置服务（设计 v1.1 §4 / §12 / §13.1 / §15）。
 *
 * <p>与旧 {@link StorageConfigService} 分离：旧七家的 CRUD/test/default 契约不变，
 * CUSTOM_HTTP 只能走本服务。凭据信封写入 {@code provider_options}，
 * 读出永不返回明文；版本变更用 {@code config_version} 手工 CAS（更新冲突 STORAGE038）。</p>
 *
 * <p>安全约束：本类不记录画像正文、凭据取值或上游响应；日志只带 configId/version/安全码。</p>
 */
@Slf4j
@Service
public class HttpStorageConfigService {

    /** 名称长度上限（与旧配置一致） */
    private static final int MAX_NAME_LENGTH = 128;

    /** 最近测试信息安全摘要上限 */
    private static final int MAX_TEST_MESSAGE_LENGTH = 200;

    private final StorageConfigMapper configMapper;

    private final StorageFileMapper fileMapper;

    private final StorageFolderConfigMapper folderConfigMapper;
    private final StoragePermissionConfigMapper permissionConfigMapper;

    private final StorageAuditService auditService;

    private final StorageConfigCache configCache;

    private final StorageProperties properties;

    private final ApprovedTargetPolicy policy;

    private final HttpProfileValidator validator;

    private final ObjectProvider<HttpChannelTestTransport> testTransport;

    private final ObjectMapper objectMapper;

    public HttpStorageConfigService(
            StorageConfigMapper configMapper,
            StorageFileMapper fileMapper,
            StorageFolderConfigMapper folderConfigMapper,
            StoragePermissionConfigMapper permissionConfigMapper,
            StorageAuditService auditService,
            StorageConfigCache configCache,
            StorageProperties properties,
            ApprovedTargetPolicy policy,
            HttpProfileValidator validator,
            ObjectProvider<HttpChannelTestTransport> testTransport,
            ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.fileMapper = fileMapper;
        this.folderConfigMapper = folderConfigMapper;
        this.permissionConfigMapper = permissionConfigMapper;
        this.auditService = auditService;
        this.configCache = configCache;
        this.properties = properties;
        this.policy = policy;
        this.validator = validator;
        this.testTransport = testTransport;
        this.objectMapper = objectMapper;
    }

    // ==================== 查询 ====================

    /** 渠道摘要列表（只含 CUSTOM_HTTP；不含 profile / 凭据） */
    public List<HttpChannelDtos.ChannelSummary> listSummaries() {
        List<StorageConfigEntity> rows = configMapper.selectList(new LambdaQueryWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getProviderType, StorageConfigEntity.PROVIDER_CUSTOM_HTTP)
                .orderByAsc(StorageConfigEntity::getId));
        List<HttpChannelDtos.ChannelSummary> result = new ArrayList<>(rows.size());
        for (StorageConfigEntity row : rows) {
            result.add(toSummary(row));
        }
        return result;
    }

    /**
     * 渠道安全详情。
     *
     * <p>信封损坏（升级/人工改库导致）时不返回 profile 与凭据，只回 {@code configError}，
     * 绝不沿用"原文返回兜底"（设计 §15）。</p>
     */
    public HttpChannelDtos.ChannelView get(Long id) {
        StorageConfigEntity config = requireChannel(id);
        HttpUploadProfile profile = null;
        List<HttpSecretEnvelope.SecretView> secrets = List.of();
        List<String> missing = List.of();
        String configError = null;
        try {
            HttpSecretEnvelope envelope = readEnvelope(config);
            profile = envelope.profile();
            secrets = envelope.secretViews(profile);
            missing = missingSecrets(envelope, profile);
        } catch (BusinessException e) {
            configError = "STORAGE034";
            log.warn("[Storage] CUSTOM_HTTP 渠道信封不可用: configId={}", config.getId());
        }
        return new HttpChannelDtos.ChannelView(
                config.getId(),
                config.getName(),
                config.getProviderType(),
                config.getStatus(),
                config.getConfigVersion(),
                config.getIsDefault(),
                config.getHealthStatus(),
                config.getLastTestTime(),
                config.getLastTestMessage(),
                config.getRemark(),
                profile,
                secrets,
                missing,
                configError,
                config.getCreateBy(),
                config.getCreateTime(),
                config.getUpdateBy(),
                config.getUpdateTime());
    }

    /** 部署侧批准目标（只读；管理接口不能扩大清单） */
    public List<HttpChannelDtos.ApprovedTargetView> approvedTargets() {
        StorageProperties.CustomHttp customHttp = properties.getCustomHttp();
        if (customHttp == null || customHttp.getApprovedTargets() == null) {
            return List.of();
        }
        List<HttpChannelDtos.ApprovedTargetView> result = new ArrayList<>();
        for (StorageProperties.ApprovedTarget raw : customHttp.getApprovedTargets()) {
            try {
                String origin = ApprovedTargetPolicy.normalizeOrigin(raw.getOrigin(), true);
                String path = ApprovedTargetPolicy.normalizePath(raw.getPath());
                List<String> urlOrigins = new ArrayList<>();
                if (raw.getUrlOrigins() != null) {
                    for (String urlOrigin : raw.getUrlOrigins()) {
                        urlOrigins.add(ApprovedTargetPolicy.normalizeOrigin(urlOrigin, true));
                    }
                }
                result.add(new HttpChannelDtos.ApprovedTargetView(origin, path, List.copyOf(urlOrigins)));
            } catch (IllegalArgumentException e) {
                log.warn("[Storage] 批准目标配置非法，已跳过: {}", e.getMessage());
            }
        }
        return result;
    }

    // ==================== 写入 ====================

    /**
     * 新建渠道：校验画像 → 写入行 → 用刚分配的 id 固化凭据绑定与信封 → 同事务内更新。
     *
     * <p>新建不允许用 KEEP/掩码伪造"已配置"：绑定从无到有，KEEP 一律 STORAGE037。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public HttpChannelDtos.ChannelView create(HttpChannelDtos.ChannelCreateReq req, String operator) {
        requireFeatureEnabled();
        if (req == null) {
            throw new BusinessException("STORAGE034", "请求不能为空");
        }
        String name = requireName(req.name(), null);
        HttpProfileValidator.Validated validated = validator.validate(req.profile());
        String status = normalizeStatus(req.status(), StorageConfigEntity.STATUS_DISABLED);
        StorageConfigEntity config = new StorageConfigEntity();
        config.setName(name);
        config.setProviderType(StorageConfigEntity.PROVIDER_CUSTOM_HTTP);
        config.setProviderOptions("{}");
        config.setMaxFileSize(
                validated.profile().limits() == null
                        ? null
                        : validated.profile().limits().maxBytes());
        config.setStatus(status);
        config.setIsDefault(0);
        config.setConfigVersion(0L);
        config.setHealthStatus(StorageConfigEntity.HEALTH_UNKNOWN);
        config.setRemark(req.remark());
        config.setCreateBy(operator);
        config.setUpdateBy(operator);
        configMapper.insert(config);
        HttpSecretEnvelope envelope = new HttpSecretEnvelope(
                        HttpSecretEnvelope.CURRENT_SCHEMA_VERSION, validated.profile(), null, List.of())
                .applyChanges(
                        validated.profile(),
                        new HttpSecretEnvelope.Binding(
                                config.getId(),
                                validated.target().getOrigin(),
                                validated.target().getPath()),
                        req.secretChanges());
        if (StorageConfigEntity.STATUS_ENABLED.equals(status)) {
            envelope.requireSecrets(validated.profile(), true);
        }
        config.setProviderOptions(HttpSecretEnvelope.write(envelope, objectMapper));
        config.setConfigVersion(1L);
        configMapper.updateById(config);
        configCache.evictAll();
        auditService.record(
                "HTTP_CONFIG_CREATE", "USER", operator, "CONFIG", String.valueOf(config.getId()), "name=" + name, "OK");
        return get(config.getId());
    }

    /**
     * 更新渠道：expectedVersion 必须与当前 {@code config_version} 一致（手工 CAS）。
     *
     * <p>画像、凭据与状态在同一版本校验事务保存；origin/path 变化时旧凭据只能 CLEAR/SET。
     * 信封损坏时允许以"空信封 + 全量 SET"修复，但会先记安全告警。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public HttpChannelDtos.ChannelView update(Long id, HttpChannelDtos.ChannelUpdateReq req, String operator) {
        requireFeatureEnabled();
        StorageConfigEntity config = requireChannel(id);
        if (req == null) {
            throw new BusinessException("STORAGE034", "请求不能为空");
        }
        if (req.expectedVersion() == null) {
            throw new BusinessException("STORAGE034", "expectedVersion 必填");
        }
        if (!req.expectedVersion().equals(config.getConfigVersion())) {
            throw new BusinessException("STORAGE038");
        }
        String name = requireName(req.name(), id);
        HttpProfileValidator.Validated validated = validator.validate(req.profile());
        String status = normalizeStatus(req.status(), config.getStatus());
        HttpSecretEnvelope current = loadEnvelopeForUpdate(config);
        HttpSecretEnvelope next = current.applyChanges(
                validated.profile(),
                new HttpSecretEnvelope.Binding(
                        config.getId(),
                        validated.target().getOrigin(),
                        validated.target().getPath()),
                req.secretChanges());
        next.requireSecrets(validated.profile(), StorageConfigEntity.STATUS_ENABLED.equals(status));
        String serialized = HttpSecretEnvelope.write(next, objectMapper);
        LambdaUpdateWrapper<StorageConfigEntity> update = new LambdaUpdateWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getId, id)
                .eq(StorageConfigEntity::getConfigVersion, req.expectedVersion())
                .set(StorageConfigEntity::getName, name)
                .set(StorageConfigEntity::getProviderOptions, serialized)
                .set(
                        StorageConfigEntity::getMaxFileSize,
                        validated.profile().limits() == null
                                ? null
                                : validated.profile().limits().maxBytes())
                .set(StorageConfigEntity::getConfigVersion, req.expectedVersion() + 1)
                .set(StorageConfigEntity::getStatus, status)
                .set(StorageConfigEntity::getHealthStatus, StorageConfigEntity.HEALTH_UNKNOWN)
                .set(StorageConfigEntity::getUpdateBy, operator)
                .set(StorageConfigEntity::getUpdateTime, LocalDateTime.now());
        if (req.remark() != null) {
            update.set(StorageConfigEntity::getRemark, req.remark());
        }
        int affected = configMapper.update(null, update);
        if (affected != 1) {
            throw new BusinessException("STORAGE038");
        }
        configCache.evictAll();
        auditService.record(
                "HTTP_CONFIG_UPDATE",
                "USER",
                operator,
                "CONFIG",
                String.valueOf(id),
                "name=" + name + ", version=" + (req.expectedVersion() + 1),
                "OK");
        return get(id);
    }

    /** 删除渠道：仍被文件/文件夹绑定/授权绑定引用时拒绝 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, String operator) {
        requireFeatureEnabled();
        StorageConfigEntity config = requireChannel(id);
        long files = countFiles(id);
        long folders = countFolderBindings(id);
        long grants = countGrantBindings(id);
        if (files > 0 || folders > 0 || grants > 0) {
            throw new BusinessException(
                    "COMMON002", "该渠道仍被引用（文件 " + files + " / 文件夹绑定 " + folders + " / 授权绑定 " + grants + "），无法删除");
        }
        configMapper.deleteById(id);
        configCache.evictAll();
        auditService.record(
                "HTTP_CONFIG_DELETE", "USER", operator, "CONFIG", String.valueOf(id), "name=" + config.getName(), "OK");
    }

    // ==================== 校验与测试 ====================

    /**
     * 离线校验：只做结构与凭据齐全性检查，不联网、不读写数据库。
     * 缺凭据时返回 {@code valid=false} 与 {@code missingSecrets}（启用的渠道必须补齐）。
     */
    public HttpChannelDtos.ChannelValidationView validateOffline(HttpChannelDtos.ChannelValidateReq req) {
        if (req == null) {
            throw new BusinessException("STORAGE034", "请求不能为空");
        }
        HttpProfileValidator.Validated validated = validator.validate(req.profile());
        HttpUploadProfile profile = validated.profile();
        Map<String, HttpSecretEnvelope.SecretChange> changes =
                req.secretChanges() == null ? Map.of() : req.secretChanges();
        for (Map.Entry<String, HttpSecretEnvelope.SecretChange> entry : changes.entrySet()) {
            if (!HttpSecretEnvelope.isValidSecretName(entry.getKey())) {
                throw new BusinessException("STORAGE037", "凭据名非法");
            }
            HttpSecretEnvelope.SecretChange change = entry.getValue();
            String op = change == null || change.op() == null
                    ? ""
                    : change.op().trim().toUpperCase(Locale.ROOT);
            switch (op) {
                case HttpSecretEnvelope.OP_SET ->
                    HttpSecretEnvelope.validateSecretValue(entry.getKey(), change.value());
                case HttpSecretEnvelope.OP_KEEP, HttpSecretEnvelope.OP_CLEAR -> {
                    // 离线态没有已存凭据，KEEP 只表示"前端未改动"，不作为已配置
                }
                default -> throw new BusinessException("STORAGE037", "不支持的凭据变更操作");
            }
        }
        List<HttpSecretEnvelope.SecretView> views = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String secretName : new TreeSet<>(HttpTemplateEngine.referencedSecretNames(profile))) {
            HttpSecretEnvelope.SecretChange change = changes.get(secretName);
            boolean configured = change != null
                    && HttpSecretEnvelope.OP_SET.equals(
                            change.op() == null ? null : change.op().trim().toUpperCase(Locale.ROOT));
            views.add(new HttpSecretEnvelope.SecretView(secretName, configured, null));
            if (!configured) {
                missing.add(secretName);
            }
        }
        return new HttpChannelDtos.ChannelValidationView(
                missing.isEmpty(), profile, List.copyOf(views), List.copyOf(missing));
    }

    /**
     * 真实渠道上传测试：使用合成内容发起一次受控请求，结果只回安全摘要。
     *
     * <p>执行器未装配或开关关闭时明确失败（STORAGE050/STORAGE039），不伪造成功。</p>
     */
    public HttpChannelDtos.ChannelTestView test(Long id, String operator) {
        requireFeatureEnabled();
        StorageConfigEntity config = requireChannel(id);
        HttpSecretEnvelope envelope = readEnvelope(config);
        HttpUploadProfile profile = envelope.profile();
        envelope.requireSecrets(profile, true);
        StorageProperties.ApprovedTarget target = policy.require(profile.endpoint());
        HttpChannelTestTransport transport = testTransport.getIfAvailable();
        if (transport == null) {
            throw new BusinessException("STORAGE050", "HTTP 渠道执行组件未安装");
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");
        int attemptMs = profile.timeouts() == null
                ? HttpUploadProfile.DEFAULT_ATTEMPT_MS
                : profile.timeouts().attemptMs();
        HttpChannelTestTransport.TestOutcome outcome;
        try {
            outcome = transport.probe(new HttpChannelTestTransport.TestRequest(
                    config.getId(),
                    config.getConfigVersion(),
                    requestId,
                    profile,
                    envelope.secretValues(),
                    target,
                    attemptMs));
        } catch (BusinessException e) {
            outcome = HttpChannelTestTransport.TestOutcome.unavailable("EXECUTION_REJECTED", "渠道执行被拒绝");
        } catch (RuntimeException e) {
            log.warn("[Storage] CUSTOM_HTTP 渠道测试执行异常: configId={}", config.getId());
            outcome = HttpChannelTestTransport.TestOutcome.unavailable("EXECUTION_ERROR", "渠道执行异常");
        }
        if (outcome == null) {
            outcome = HttpChannelTestTransport.TestOutcome.unavailable("NO_RESULT", "渠道执行未返回结果");
        }
        LocalDateTime testedAt = LocalDateTime.now();
        String healthStatus = outcome.success()
                ? StorageConfigEntity.HEALTH_UP
                : ("AUTH_REJECTED".equals(outcome.safeCode())
                        ? StorageConfigEntity.HEALTH_AUTH_INVALID
                        : StorageConfigEntity.HEALTH_DOWN);
        String message = safeTestMessage(outcome);
        configMapper.update(
                null,
                new LambdaUpdateWrapper<StorageConfigEntity>()
                        .eq(StorageConfigEntity::getId, id)
                        .set(StorageConfigEntity::getHealthStatus, healthStatus)
                        .set(StorageConfigEntity::getLastTestTime, testedAt)
                        .set(StorageConfigEntity::getLastTestMessage, message)
                        .set(StorageConfigEntity::getUpdateBy, operator)
                        .set(StorageConfigEntity::getUpdateTime, testedAt));
        configCache.evictAll();
        auditService.record(
                "HTTP_CONFIG_TEST",
                "USER",
                operator,
                "CONFIG",
                String.valueOf(id),
                "requestId=" + requestId + ", code=" + outcome.safeCode(),
                outcome.success() ? "OK" : "FAIL");
        return new HttpChannelDtos.ChannelTestView(
                outcome.success(),
                outcome.safeCode(),
                outcome.safeMessage(),
                outcome.httpStatus(),
                outcome.remoteSizeBytes(),
                config.getId(),
                config.getConfigVersion(),
                requestId,
                testedAt);
    }

    // ==================== 内部 ====================

    private void requireFeatureEnabled() {
        if (!policy.enabled()) {
            throw new BusinessException("STORAGE039");
        }
    }

    private StorageConfigEntity requireChannel(Long id) {
        if (id == null) {
            throw new BusinessException("STORAGE002");
        }
        StorageConfigEntity config = configMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("STORAGE002");
        }
        if (!StorageConfigEntity.PROVIDER_CUSTOM_HTTP.equalsIgnoreCase(trim(config.getProviderType()))) {
            throw new BusinessException("STORAGE039");
        }
        return config;
    }

    private String requireName(String rawName, Long excludeId) {
        if (rawName == null || rawName.isBlank()) {
            throw new BusinessException("STORAGE034", "name 不能为空");
        }
        String name = rawName.trim();
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException("STORAGE034", "name 不能超过 " + MAX_NAME_LENGTH + " 个字符");
        }
        LambdaQueryWrapper<StorageConfigEntity> query =
                new LambdaQueryWrapper<StorageConfigEntity>().eq(StorageConfigEntity::getName, name);
        if (excludeId != null) {
            query.ne(StorageConfigEntity::getId, excludeId);
        }
        Long duplicate = configMapper.selectCount(query);
        if (duplicate != null && duplicate > 0) {
            throw new BusinessException("COMMON002", "配置名称已存在");
        }
        return name;
    }

    private String normalizeStatus(String rawStatus, String fallback) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return fallback == null ? StorageConfigEntity.STATUS_DISABLED : fallback;
        }
        String status = rawStatus.trim().toUpperCase(Locale.ROOT);
        if (!StorageConfigEntity.STATUS_ENABLED.equals(status) && !StorageConfigEntity.STATUS_DISABLED.equals(status)) {
            throw new BusinessException("STORAGE034", "status 只支持 ENABLED / DISABLED");
        }
        return status;
    }

    private HttpSecretEnvelope readEnvelope(StorageConfigEntity config) {
        HttpSecretEnvelope envelope = HttpSecretEnvelope.parse(config.getProviderOptions());
        if (envelope.binding() == null
                || !config.getId().equals(envelope.binding().channelId())) {
            throw new BusinessException("STORAGE034", "渠道凭据绑定与配置不一致");
        }
        return envelope;
    }

    /**
     * 更新前读取信封：损坏时按空信封处理（绑定视为已变化，旧凭据无法 KEEP），
     * 使管理员能以全量 SET 修复；不把损坏原文写回。
     */
    private HttpSecretEnvelope loadEnvelopeForUpdate(StorageConfigEntity config) {
        try {
            return readEnvelope(config);
        } catch (BusinessException e) {
            log.warn("[Storage] CUSTOM_HTTP 渠道信封损坏，按空信封修复: configId={}", config.getId());
            return new HttpSecretEnvelope(HttpSecretEnvelope.CURRENT_SCHEMA_VERSION, null, null, List.of());
        }
    }

    private static List<String> missingSecrets(HttpSecretEnvelope envelope, HttpUploadProfile profile) {
        List<String> missing = new ArrayList<>();
        for (String name : new TreeSet<>(HttpTemplateEngine.referencedSecretNames(profile))) {
            if (envelope.secretValues().get(name) == null) {
                missing.add(name);
            }
        }
        return List.copyOf(missing);
    }

    private static HttpChannelDtos.ChannelSummary toSummary(StorageConfigEntity config) {
        return new HttpChannelDtos.ChannelSummary(
                config.getId(),
                config.getName(),
                config.getProviderType(),
                config.getStatus(),
                config.getConfigVersion(),
                config.getIsDefault(),
                config.getHealthStatus(),
                config.getLastTestTime(),
                config.getLastTestMessage(),
                config.getRemark(),
                config.getCreateBy(),
                config.getCreateTime(),
                config.getUpdateBy(),
                config.getUpdateTime());
    }

    private static String safeTestMessage(HttpChannelTestTransport.TestOutcome outcome) {
        String message = outcome.safeMessage();
        if (message == null || message.isBlank()) {
            message = outcome.safeCode();
        }
        if (message == null) {
            return null;
        }
        return message.length() > MAX_TEST_MESSAGE_LENGTH ? message.substring(0, MAX_TEST_MESSAGE_LENGTH) : message;
    }

    private long countFiles(Long configId) {
        Long count = fileMapper.selectCount(
                new LambdaQueryWrapper<StorageFileEntity>().eq(StorageFileEntity::getStorageConfigId, configId));
        return count == null ? 0L : count;
    }

    private long countFolderBindings(Long configId) {
        Long count = folderConfigMapper.selectCount(new LambdaQueryWrapper<StorageFolderConfigEntity>()
                .eq(StorageFolderConfigEntity::getStorageConfigId, configId));
        return count == null ? 0L : count;
    }

    private long countGrantBindings(Long configId) {
        Long count = permissionConfigMapper.selectCount(new LambdaQueryWrapper<StoragePermissionConfigEntity>()
                .eq(StoragePermissionConfigEntity::getStorageConfigId, configId));
        return count == null ? 0L : count;
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
