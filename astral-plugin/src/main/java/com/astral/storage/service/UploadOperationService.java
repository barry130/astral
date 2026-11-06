package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import com.astral.storage.dto.UploadOperationDtos;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.entity.StorageUploadOperationEntity;
import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageUploadOperationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 两段式上传操作（§7.2–§7.4、§8.4）。
 *
 * <p>事务边界是「短事务 + 外部调用置于事务外」：</p>
 * <ol>
 *   <li>创建意图：幂等键查询先于目标解析，只落库不占许可证/配额；</li>
 *   <li>PUT 预检：身份→归属→目录 UPLOAD 权限→目标版本→准入→配额 HELD→CAS CREATED→RECEIVING（同一事务）；</li>
 *   <li>正文校验通过后把 SENDING 提交，**再**发起上游调用（崩溃后不会重复发送）；</li>
 *   <li>结果落库：COMMITTED 与文件登记同事务，配额 HELD→CONSUMED；确定未发出才退回 CREATED 换目标。</li>
 * </ol>
 *
 * <p>只有 {@code COMMITTED} 是成功；HTTP 200 只是传输结果。</p>
 */
@Slf4j
@Service
public class UploadOperationService {

    private static final String ACTOR_USER = StorageUploadOperationEntity.ACTOR_USER;
    private static final int MAX_CONCURRENT_INTENTS_PER_USER = 2;
    private static final int MAX_CONCURRENT_INTENTS = 64;
    private static final int MAX_ATTEMPTS = 2;

    private final StorageUploadOperationMapper operationMapper;
    private final StorageFileMapper fileMapper;
    private final StorageFolderService folderService;
    private final UploadPolicyService policyService;
    private final UploadQuotaService quotaService;
    private final UploadAdmissionControl admissionControl;
    private final UploadImageValidator imageValidator;
    private final StorageTargetResolver targetResolver;
    private final HttpUploadGateway gateway;
    private final StorageProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public UploadOperationService(
            StorageUploadOperationMapper operationMapper,
            StorageFileMapper fileMapper,
            StorageFolderService folderService,
            UploadPolicyService policyService,
            UploadQuotaService quotaService,
            UploadAdmissionControl admissionControl,
            UploadImageValidator imageValidator,
            StorageTargetResolver targetResolver,
            HttpUploadGateway gateway,
            StorageProperties properties,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.operationMapper = operationMapper;
        this.fileMapper = fileMapper;
        this.folderService = folderService;
        this.policyService = policyService;
        this.quotaService = quotaService;
        this.admissionControl = admissionControl;
        this.imageValidator = imageValidator;
        this.targetResolver = targetResolver;
        this.gateway = gateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** PUT 预检结果（正文读取前返回，读取完成后交给 {@link #sendContent}） */
    public record PreparedContent(
            StorageUploadOperationEntity operation,
            UploadPolicyService.PolicySpec policy,
            String leaseToken,
            long maxBytes,
            UploadAdmissionControl.Permit permit)
            implements AutoCloseable {

        @Override
        public void close() {
            if (permit != null) {
                permit.close();
            }
        }
    }

    // ==================== 创建意图 ====================

    public UploadOperationDtos.UploadCreateResp create(
            UploadOperationDtos.UploadCreateReq req, String userId, String idempotencyKey, boolean folderAclExempt) {
        requireLogin(userId);
        String keyHash = hashIdempotencyKey(idempotencyKey);
        validateCreateRequest(req);
        StorageFolderEntity folder = folderService.getById(req.folderId());
        if (!folderAclExempt) {
            folderService.requirePermission(userId, folder.getId(), "UPLOAD");
        }
        requirePublicFolder(folder);
        UploadPolicyService.PolicySpec policy = policyService.resolve(folder);
        long maxBytes = maxContentBytes(policy);
        String fileName = normalizeFileName(req.fileName());
        String mime = normalizeMime(req.contentType());
        long size = req.sizeBytes();
        if (size <= 0 || size > maxBytes) {
            throw new BusinessException("STORAGE045", "声明大小超出目录允许范围");
        }
        if (policy.minSizeBytes() > 0 && size < policy.minSizeBytes()) {
            throw new BusinessException("STORAGE045", "声明大小低于目录下限");
        }
        checkPolicyContent(policy, mime, fileName);
        String sha256 = normalizeSha256(req.sha256());

        String fingerprint = intentFingerprint(userId, folder, fileName, mime, size, sha256);
        StorageUploadOperationEntity existing = findByKey(userId, keyHash);
        if (existing != null) {
            if (!fingerprint.equals(existing.getIntentFingerprint())) {
                throw new BusinessException("STORAGE043");
            }
            return toCreateResp(existing, maxBytes);
        }
        enforceIntentBudget(userId);

        StorageTargetResolver.Resolution resolution = targetResolver.resolveForUpload(folder, userId);
        StorageTargetResolver.Candidate candidate = resolution.firstCandidate();
        LocalDateTime now = LocalDateTime.now();
        StorageUploadOperationEntity operation = new StorageUploadOperationEntity();
        operation.setUploadId(randomId());
        operation.setPublicId(randomId());
        operation.setActorType(ACTOR_USER);
        operation.setActorId(userId);
        operation.setIdempotencyKeyHash(keyHash);
        operation.setIntentFingerprint(fingerprint);
        operation.setOriginalName(fileName);
        operation.setExpectedMime(mime);
        operation.setExpectedSize(size);
        operation.setExpectedSha256(sha256);
        operation.setFolderId(folder.getId());
        operation.setFolderVersion(folder.getTargetVersion());
        operation.setRouteSnapshotJson(resolution.routeSnapshotJson());
        operation.setChosenConfigId(candidate.configId());
        operation.setChosenConfigVersion(candidate.configVersion());
        operation.setState(StorageUploadOperationEntity.STATE_CREATED);
        operation.setVersion(0L);
        operation.setQuotaCharge(StorageUploadOperationEntity.CHARGE_NONE);
        operation.setQuotaExempt(0);
        operation.setPurpose(StorageUploadOperationEntity.PURPOSE_UPLOAD);
        operation.setAuditPending(0);
        operation.setAttemptsJson("[]");
        operation.setIntentExpiresAt(now.plusSeconds(intentTtlSeconds()));
        operation.setSendDeadline(now.plusSeconds(deadlineSeconds()));
        operation.setCreateTime(now);
        operation.setUpdateTime(now);
        try {
            operationMapper.insert(operation);
        } catch (DuplicateKeyException ex) {
            StorageUploadOperationEntity raced = findByKey(userId, keyHash);
            if (raced == null) {
                throw ex;
            }
            if (!fingerprint.equals(raced.getIntentFingerprint())) {
                throw new BusinessException("STORAGE043");
            }
            return toCreateResp(raced, maxBytes);
        }
        log.info("上传意图已创建: uploadId={}, folderId={}, actorId={}", operation.getUploadId(), folder.getId(), userId);
        return toCreateResp(operation, maxBytes);
    }

    // ==================== 查询 ====================

    public UploadOperationDtos.UploadOperationView get(String uploadId, String userId, boolean skipOwnerCheck) {
        StorageUploadOperationEntity operation = requireOwned(uploadId, userId, skipOwnerCheck);
        return toView(operation);
    }

    // ==================== PUT 预检 ====================

    public PreparedContent prepareContent(
            String uploadId,
            Long contentLength,
            String contentType,
            String userId,
            boolean folderAclExempt,
            boolean skipOwnerCheck) {
        requireLogin(userId);
        StorageUploadOperationEntity snapshot = requireOwned(uploadId, userId, skipOwnerCheck);
        UploadAdmissionControl.Permit permit = admissionControl.acquire(snapshot.getUploadId(), snapshot.getActorId());
        try {
            PreparedContent prepared = transactionTemplate.execute(status -> claimForReceiving(
                    uploadId, contentLength, contentType, userId, folderAclExempt, skipOwnerCheck, permit));
            if (prepared == null) {
                throw new BusinessException("STORAGE055");
            }
            return prepared;
        } catch (RuntimeException ex) {
            permit.close();
            throw ex;
        }
    }

    private PreparedContent claimForReceiving(
            String uploadId,
            Long contentLength,
            String contentType,
            String userId,
            boolean folderAclExempt,
            boolean skipOwnerCheck,
            UploadAdmissionControl.Permit permit) {
        StorageUploadOperationEntity operation = requireOwned(uploadId, userId, skipOwnerCheck);
        LocalDateTime now = LocalDateTime.now();
        if (StorageUploadOperationEntity.STATE_EXPIRED.equals(operation.getState())
                || operation.getIntentExpiresAt() == null
                || !operation.getIntentExpiresAt().isAfter(now)) {
            throw new BusinessException("STORAGE044");
        }
        if (now.isAfter(operation.getSendDeadline())) {
            throw new BusinessException("STORAGE056");
        }
        if (!StorageUploadOperationEntity.STATE_CREATED.equals(operation.getState())) {
            throw new BusinessException("STORAGE055");
        }
        // Content-Length 头只是快速失败优化：反向代理（如 Next.js rewrites）转发正文
        // 可能改用 chunked 传输（无 Content-Length，Servlet 取到 -1），此时不做头级比对，
        // 交给 readBounded 的 maxBytes 硬上限与 validateContent 的实际字节校验
        // （长度 + SHA-256 + 图片格式）兜底。头存在且与声明不符时仍然快速失败。
        boolean hasDeclaredLength = contentLength != null && contentLength >= 0;
        if (hasDeclaredLength && contentLength.longValue() != operation.getExpectedSize()) {
            throw new BusinessException(
                    "STORAGE045",
                    "Content-Length 与声明大小不一致（收到 " + contentLength + "，声明 " + operation.getExpectedSize() + "）");
        }
        if (!normalizeMime(contentType).equals(operation.getExpectedMime())) {
            throw new BusinessException("STORAGE045", "Content-Type 与声明 MIME 不一致");
        }
        StorageFolderEntity folder = folderService.getById(operation.getFolderId());
        if (!folderAclExempt) {
            folderService.requirePermission(userId, folder.getId(), "UPLOAD");
        }
        // 意图创建后目录可见性可能被改回 PRIVATE：接收前再检一次，避免非公开正文被代传到上游（§11.3）。
        requirePublicFolder(folder);
        UploadPolicyService.PolicySpec policy = policyService.resolve(folder);
        if (hasDeclaredLength && contentLength > maxContentBytes(policy)) {
            throw new BusinessException("STORAGE045", "正文超出允许上限");
        }
        long currentVersion = folder.getTargetVersion() == null ? 1L : folder.getTargetVersion();
        long operationVersion = operation.getFolderVersion() == null ? 1L : operation.getFolderVersion();
        if (currentVersion != operationVersion) {
            throw new BusinessException("STORAGE047");
        }
        quotaService.reserve(operation, policy.dailyUploadLimit() == null ? 0 : policy.dailyUploadLimit());
        String leaseToken = randomId();
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds());
        LambdaUpdateWrapper<StorageUploadOperationEntity> update = Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                .eq(StorageUploadOperationEntity::getId, operation.getId())
                .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_CREATED)
                .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_RECEIVING)
                .set(StorageUploadOperationEntity::getLeaseToken, leaseToken)
                .set(StorageUploadOperationEntity::getLeaseUntil, leaseUntil)
                .set(StorageUploadOperationEntity::getQuotaDay, operation.getQuotaDay())
                .set(StorageUploadOperationEntity::getQuotaCharge, operation.getQuotaCharge())
                .set(StorageUploadOperationEntity::getQuotaExempt, operation.getQuotaExempt())
                .set(StorageUploadOperationEntity::getVersion, operation.getVersion() + 1)
                .set(StorageUploadOperationEntity::getUpdateTime, now);
        if (operationMapper.update(null, update) == 0) {
            throw new BusinessException("STORAGE055");
        }
        operation.setState(StorageUploadOperationEntity.STATE_RECEIVING);
        operation.setLeaseToken(leaseToken);
        operation.setLeaseUntil(leaseUntil);
        operation.setVersion(operation.getVersion() + 1);
        return new PreparedContent(operation, policy, leaseToken, maxContentBytes(policy), permit);
    }

    // ==================== 发送并落库 ====================

    public UploadOperationDtos.UploadContentResp sendContent(PreparedContent prepared, byte[] content) {
        UploadOperationEntityHolder holder = new UploadOperationEntityHolder(prepared.operation());
        try {
            StorageUploadOperationEntity operation = holder.operation;
            validateContent(operation, prepared.policy(), content);
            HttpUploadGateway.Channel channel = gateway.loadChannel(operation.getChosenConfigId());
            long expectedVersion = operation.getChosenConfigVersion() == null ? 0L : operation.getChosenConfigVersion();
            if (channel.configVersion() != expectedVersion) {
                throw new BusinessException("STORAGE047");
            }
            StorageUploadOperationEntity sending = markSending(operation.getId(), prepared.leaseToken());
            String sha256 = sha256Hex(content);
            HttpUploadGateway.Attempt attempt = new HttpUploadGateway.Attempt(
                    sending.getUploadId(),
                    sending.getPublicId(),
                    sending.getOriginalName(),
                    sending.getExpectedMime(),
                    content.length,
                    content,
                    sha256);
            HttpUploadGateway.Result result = gateway.send(channel, attempt);
            return applyResult(sending, result, content, sha256);
        } catch (RuntimeException ex) {
            revertToCreatedQuietly(holder.operation, prepared.leaseToken());
            throw ex;
        } finally {
            prepared.close();
        }
    }

    /** 仅供 orElse 场景持有可变引用 */
    private static final class UploadOperationEntityHolder {
        private final StorageUploadOperationEntity operation;

        private UploadOperationEntityHolder(StorageUploadOperationEntity operation) {
            this.operation = operation;
        }
    }

    private void validateContent(
            StorageUploadOperationEntity operation, UploadPolicyService.PolicySpec policy, byte[] content) {
        if (content == null || content.length != operation.getExpectedSize()) {
            throw new BusinessException("STORAGE045", "正文长度与声明不一致");
        }
        String actualSha = sha256Hex(content);
        if (operation.getExpectedSha256() != null
                && !operation.getExpectedSha256().equals(actualSha)) {
            throw new BusinessException("STORAGE045", "正文摘要与声明不一致");
        }
        if (operation.getExpectedMime() != null && operation.getExpectedMime().startsWith("image/")) {
            imageValidator.validate(content, operation.getExpectedMime());
        }
    }

    private StorageUploadOperationEntity markSending(Long id, String leaseToken) {
        LocalDateTime now = LocalDateTime.now();
        StorageUploadOperationEntity current = operationMapper.selectById(id);
        if (current == null) {
            throw new BusinessException("STORAGE054");
        }
        if (!StorageUploadOperationEntity.STATE_RECEIVING.equals(current.getState())
                || !leaseToken.equals(current.getLeaseToken())) {
            throw new BusinessException("STORAGE055");
        }
        LambdaUpdateWrapper<StorageUploadOperationEntity> update = Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                .eq(StorageUploadOperationEntity::getId, id)
                .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_RECEIVING)
                .eq(StorageUploadOperationEntity::getLeaseToken, leaseToken)
                .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_SENDING)
                .set(StorageUploadOperationEntity::getLeaseUntil, now.plusSeconds(leaseSeconds()))
                .set(StorageUploadOperationEntity::getVersion, current.getVersion() + 1)
                .set(StorageUploadOperationEntity::getUpdateTime, now);
        if (operationMapper.update(null, update) == 0) {
            throw new BusinessException("STORAGE055");
        }
        current.setState(StorageUploadOperationEntity.STATE_SENDING);
        current.setVersion(current.getVersion() + 1);
        return current;
    }

    private UploadOperationDtos.UploadContentResp applyResult(
            StorageUploadOperationEntity sending, HttpUploadGateway.Result result, byte[] content, String sha256) {
        UploadOperationDtos.UploadContentResp response = transactionTemplate.execute(status -> {
            StorageUploadOperationEntity current = operationMapper.selectById(sending.getId());
            if (current == null) {
                throw new BusinessException("STORAGE054");
            }
            if (!StorageUploadOperationEntity.STATE_SENDING.equals(current.getState())) {
                throw new BusinessException("STORAGE055");
            }
            LocalDateTime now = LocalDateTime.now();
            switch (result.kind()) {
                case REMOTE_CREATED -> {
                    StorageFileEntity file = registerCommittedFile(current, result, content, sha256, now);
                    current.setState(StorageUploadOperationEntity.STATE_COMMITTED);
                    current.setQuotaCharge(StorageUploadOperationEntity.CHARGE_CONSUMED);
                    current.setFileId(file.getId());
                    current.setSafeResultJson(writeJson(
                            result.url(), result.thumbUrl(), result.remoteId(), result.width(), result.height()));
                    current.setActualSize((long) content.length);
                    current.setActualSha256(sha256);
                    current.setCompletedAt(now);
                    persistResult(current, now);
                    log.info("上传已完成: uploadId={}, fileId={}", current.getUploadId(), file.getId());
                }
                case DEFINITE_REJECTED -> {
                    current.setState(StorageUploadOperationEntity.STATE_FAILED);
                    current.setFailureCode(result.safeCode());
                    current.setQuotaCharge(StorageUploadOperationEntity.CHARGE_REFUNDED);
                    current.setActualSize((long) content.length);
                    current.setActualSha256(sha256);
                    current.setCompletedAt(now);
                    persistResult(current, now);
                }
                case UNKNOWN -> {
                    current.setState(StorageUploadOperationEntity.STATE_UNKNOWN_OUTCOME);
                    current.setFailureCode(result.safeCode());
                    current.setActualSize((long) content.length);
                    current.setActualSha256(sha256);
                    persistResult(current, now);
                }
                case NOT_SENT -> switchToNextTarget(current, result.safeCode(), now);
            }
            return new UploadOperationDtos.UploadContentResp(
                    true, toView(current), result.httpStatus(), result.safeCode());
        });
        if (response == null) {
            throw new BusinessException("STORAGE055");
        }
        return response;
    }

    private StorageFileEntity registerCommittedFile(
            StorageUploadOperationEntity operation,
            HttpUploadGateway.Result result,
            byte[] content,
            String sha256,
            LocalDateTime now) {
        StorageFileEntity file = new StorageFileEntity();
        file.setPublicId(randomId());
        file.setUploadId(operation.getUploadId());
        file.setUploadOperationId(operation.getId());
        file.setFolderId(operation.getFolderId());
        file.setStorageConfigId(operation.getChosenConfigId());
        file.setProviderType(HttpUploadGateway.PROVIDER_CUSTOM_HTTP);
        file.setProviderLocatorJson(
                writeJson(result.url(), result.thumbUrl(), result.remoteId(), result.width(), result.height()));
        file.setOriginalName(operation.getOriginalName());
        file.setContentType(operation.getExpectedMime());
        file.setSizeBytes((long) content.length);
        file.setChecksum(sha256);
        file.setContentVersion(1L);
        // §11.3：代传落库的文件一律 PUBLIC（外发资源；目录可见性不随文件行复制）
        file.setVisibility(StorageFolderEntity.VISIBILITY_PUBLIC);
        file.setStatus(StorageFileEntity.STATUS_AVAILABLE);
        file.setUploaderType(ACTOR_USER);
        file.setUploaderId(operation.getActorId());
        file.setCreateTime(now);
        file.setUpdateTime(now);
        fileMapper.insert(file);
        return file;
    }

    private void persistResult(StorageUploadOperationEntity operation, LocalDateTime now) {
        LambdaUpdateWrapper<StorageUploadOperationEntity> update = Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                .eq(StorageUploadOperationEntity::getId, operation.getId())
                .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_SENDING)
                .set(StorageUploadOperationEntity::getState, operation.getState())
                .set(StorageUploadOperationEntity::getFileId, operation.getFileId())
                .set(StorageUploadOperationEntity::getSafeResultJson, operation.getSafeResultJson())
                .set(StorageUploadOperationEntity::getFailureCode, operation.getFailureCode())
                .set(StorageUploadOperationEntity::getActualSize, operation.getActualSize())
                .set(StorageUploadOperationEntity::getActualSha256, operation.getActualSha256())
                .set(StorageUploadOperationEntity::getQuotaCharge, operation.getQuotaCharge())
                .set(StorageUploadOperationEntity::getCompletedAt, operation.getCompletedAt())
                .set(StorageUploadOperationEntity::getLeaseToken, null)
                .set(StorageUploadOperationEntity::getLeaseUntil, null)
                .set(StorageUploadOperationEntity::getVersion, operation.getVersion() + 1)
                .set(StorageUploadOperationEntity::getUpdateTime, now);
        if (operationMapper.update(null, update) == 0) {
            throw new BusinessException("STORAGE055");
        }
        operation.setVersion(operation.getVersion() + 1);
    }

    /**
     * 确定未发出（连接被拒/TLS/DNS 失败）：若还有下一个候选则退回 CREATED 换目标，否则判定失败并退回额度。
     * 其他任何失败（超时、5xx、无法解析）都不会走到这里——它们可能是「已创建」，只记录不重试。
     */
    private void switchToNextTarget(StorageUploadOperationEntity operation, String safeCode, LocalDateTime now) {
        StorageUploadOperationEntity next = resolveNextCandidate(operation);
        if (next == null) {
            operation.setState(StorageUploadOperationEntity.STATE_FAILED);
            operation.setFailureCode(safeCode);
            operation.setQuotaCharge(StorageUploadOperationEntity.CHARGE_REFUNDED);
            operation.setCompletedAt(now);
            persistResult(operation, now);
            return;
        }
        operation.setChosenConfigId(next.getChosenConfigId());
        operation.setChosenConfigVersion(next.getChosenConfigVersion());
        operation.setState(StorageUploadOperationEntity.STATE_CREATED);
        operation.setAttemptsJson(next.getAttemptsJson());
        LambdaUpdateWrapper<StorageUploadOperationEntity> update = Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                .eq(StorageUploadOperationEntity::getId, operation.getId())
                .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_SENDING)
                .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_CREATED)
                .set(StorageUploadOperationEntity::getChosenConfigId, operation.getChosenConfigId())
                .set(StorageUploadOperationEntity::getChosenConfigVersion, operation.getChosenConfigVersion())
                .set(StorageUploadOperationEntity::getAttemptsJson, operation.getAttemptsJson())
                .set(StorageUploadOperationEntity::getFailureCode, safeCode)
                .set(StorageUploadOperationEntity::getLeaseToken, null)
                .set(StorageUploadOperationEntity::getLeaseUntil, null)
                .set(StorageUploadOperationEntity::getVersion, operation.getVersion() + 1)
                .set(StorageUploadOperationEntity::getUpdateTime, now);
        if (operationMapper.update(null, update) == 0) {
            throw new BusinessException("STORAGE055");
        }
        operation.setVersion(operation.getVersion() + 1);
        log.info("上传目标不可达，已切换候选: uploadId={}, code={}", operation.getUploadId(), safeCode);
    }

    private StorageUploadOperationEntity resolveNextCandidate(StorageUploadOperationEntity operation) {
        try {
            StorageFolderEntity folder = folderService.getById(operation.getFolderId());
            StorageTargetResolver.Resolution resolution =
                    targetResolver.resolveForUpload(folder, operation.getActorId());
            List<StorageTargetResolver.Candidate> candidates = resolution.flatCandidates();
            int attempts = countAttempts(operation.getAttemptsJson());
            if (attempts >= MAX_ATTEMPTS) {
                return null;
            }
            // §10.2「最多尝试 MAX_ATTEMPTS 个不同渠道」：排除当前失败渠道与 attemptsJson 里已试过的
            // 全部渠道，避免 A→B→A 循环重发（JSON 反序列化数值类型不保证 Long，统一按字符串比对）。
            java.util.Set<String> attempted = readList(operation.getAttemptsJson()).stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet());
            attempted.add(String.valueOf(operation.getChosenConfigId()));
            for (StorageTargetResolver.Candidate candidate : candidates) {
                if (!attempted.contains(String.valueOf(candidate.configId()))) {
                    StorageUploadOperationEntity next = new StorageUploadOperationEntity();
                    next.setChosenConfigId(candidate.configId());
                    next.setChosenConfigVersion(candidate.configVersion());
                    next.setAttemptsJson(appendAttempt(operation.getAttemptsJson(), operation.getChosenConfigId()));
                    return next;
                }
            }
            return null;
        } catch (RuntimeException ex) {
            log.warn("解析备用目标失败: uploadId={}, reason={}", operation.getUploadId(), ex.getMessage());
            return null;
        }
    }

    private void revertToCreatedQuietly(StorageUploadOperationEntity operation, String leaseToken) {
        try {
            LambdaUpdateWrapper<StorageUploadOperationEntity> update =
                    Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                            .eq(StorageUploadOperationEntity::getId, operation.getId())
                            .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_RECEIVING)
                            .eq(StorageUploadOperationEntity::getLeaseToken, leaseToken)
                            .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_CREATED)
                            .set(StorageUploadOperationEntity::getLeaseToken, null)
                            .set(StorageUploadOperationEntity::getLeaseUntil, null)
                            .set(StorageUploadOperationEntity::getUpdateTime, LocalDateTime.now());
            operationMapper.update(null, update);
        } catch (RuntimeException ex) {
            log.warn("回退上传意图失败（留待恢复任务）: uploadId={}, reason={}", operation.getUploadId(), ex.getMessage());
        }
    }

    // ==================== 校验与工具 ====================

    private StorageUploadOperationEntity requireOwned(String uploadId, String userId, boolean skipOwnerCheck) {
        StorageUploadOperationEntity operation =
                operationMapper.selectOne(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                        .eq(StorageUploadOperationEntity::getUploadId, uploadId));
        if (operation == null) {
            throw new BusinessException("STORAGE054");
        }
        if (!skipOwnerCheck && !operation.getActorId().equals(userId)) {
            throw new BusinessException("STORAGE054");
        }
        return operation;
    }

    private StorageUploadOperationEntity findByKey(String userId, String keyHash) {
        return operationMapper.selectOne(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                .eq(StorageUploadOperationEntity::getActorType, ACTOR_USER)
                .eq(StorageUploadOperationEntity::getActorId, userId)
                .eq(StorageUploadOperationEntity::getIdempotencyKeyHash, keyHash));
    }

    private void enforceIntentBudget(String userId) {
        reclaimExpiredIntents();
        Long perUser = operationMapper.selectCount(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                .eq(StorageUploadOperationEntity::getActorType, ACTOR_USER)
                .eq(StorageUploadOperationEntity::getActorId, userId)
                .in(
                        StorageUploadOperationEntity::getState,
                        List.of(
                                StorageUploadOperationEntity.STATE_CREATED,
                                StorageUploadOperationEntity.STATE_RECEIVING)));
        if (perUser != null && perUser >= MAX_CONCURRENT_INTENTS_PER_USER) {
            throw new BusinessException("STORAGE042");
        }
        Long global = operationMapper.selectCount(new LambdaQueryWrapper<StorageUploadOperationEntity>()
                .in(
                        StorageUploadOperationEntity::getState,
                        List.of(
                                StorageUploadOperationEntity.STATE_CREATED,
                                StorageUploadOperationEntity.STATE_RECEIVING)));
        if (global != null && global >= MAX_CONCURRENT_INTENTS) {
            throw new BusinessException("STORAGE042");
        }
    }

    /**
     * 回收已过期的意图名额。
     *
     * 原逻辑只在 PUT 同一 uploadId 时懒标记过期；失败重试永远拿新幂等键、
     * 不会触达旧意图，卡在 CREATED/RECEIVING 的意图会永久占用并发名额，
     * 把每用户/全局预算耗尽成 STORAGE042 死锁。这里在计数前统一回收：
     * CREATED 且 intent_expires_at 已过 → EXPIRED；RECEIVING 且租约已过 → EXPIRED。
     */
    private void reclaimExpiredIntents() {
        LocalDateTime now = LocalDateTime.now();
        // 过期行统一标 EXPIRED（豁免行也要标，否则会一直占用并发名额）。
        operationMapper.update(
                null,
                Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                        .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_CREATED)
                        .lt(StorageUploadOperationEntity::getIntentExpiresAt, now)
                        .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_EXPIRED)
                        .set(StorageUploadOperationEntity::getUpdateTime, now));
        operationMapper.update(
                null,
                Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                        .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_RECEIVING)
                        .lt(StorageUploadOperationEntity::getLeaseUntil, now)
                        .set(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_EXPIRED)
                        .set(StorageUploadOperationEntity::getUpdateTime, now));
        // 退额度（§9.1/§13.3）：usage() 对 quota_charge 无状态过滤，不退会永久占用当日额度。
        // 只退 HELD 行，NONE/EXEMPT 豁免与 NULL（未预占）保持原样，不产生语义噪音。
        operationMapper.update(
                null,
                Wrappers.<StorageUploadOperationEntity>lambdaUpdate()
                        .eq(StorageUploadOperationEntity::getState, StorageUploadOperationEntity.STATE_EXPIRED)
                        .eq(StorageUploadOperationEntity::getQuotaCharge, StorageUploadOperationEntity.CHARGE_HELD)
                        .set(StorageUploadOperationEntity::getQuotaCharge, StorageUploadOperationEntity.CHARGE_REFUNDED)
                        .set(StorageUploadOperationEntity::getUpdateTime, now));
    }

    private void requirePublicFolder(StorageFolderEntity folder) {
        if (!StorageFolderEntity.VISIBILITY_PUBLIC.equals(folder.getVisibility())) {
            throw new BusinessException("STORAGE059", "仅公开目录可使用服务端代传");
        }
    }

    private void validateCreateRequest(UploadOperationDtos.UploadCreateReq req) {
        if (req == null || req.folderId() == null) {
            throw new BusinessException("COMMON002", "缺少目标目录");
        }
        if (req.fileName() == null || req.fileName().isBlank()) {
            throw new BusinessException("COMMON002", "缺少文件名");
        }
        if (req.contentType() == null || req.contentType().isBlank()) {
            throw new BusinessException("COMMON002", "缺少 MIME");
        }
        if (req.sizeBytes() == null || req.sizeBytes() <= 0) {
            throw new BusinessException("COMMON002", "缺少有效大小");
        }
    }

    private void checkPolicyContent(UploadPolicyService.PolicySpec policy, String mime, String fileName) {
        if (!policy.allowedMimes().isEmpty()
                && policy.allowedMimes().stream().noneMatch(allowed -> allowed.equalsIgnoreCase(mime))) {
            throw new BusinessException("STORAGE033", "MIME 不在目录允许范围");
        }
        if (!policy.allowedExtensions().isEmpty()) {
            String extension = extensionOf(fileName);
            boolean allowed =
                    policy.allowedExtensions().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(extension));
            if (!allowed) {
                throw new BusinessException("STORAGE033", "扩展名不在目录允许范围");
            }
        }
        long max = maxContentBytes(policy);
        if (policy.maxSizeBytes() != null && policy.maxSizeBytes() > max) {
            log.debug("目录上限高于全局正文上限，按全局上限处理: {}", policy.maxSizeBytes());
        }
    }

    private long maxContentBytes(UploadPolicyService.PolicySpec policy) {
        long global = properties.getUploadOperation().getMaxContentBytes();
        long configured = policy.maxSizeBytes() == null ? global : policy.maxSizeBytes();
        return Math.min(global, configured);
    }

    private int intentTtlSeconds() {
        return properties.getUploadTicketTtlSeconds();
    }

    private int deadlineSeconds() {
        return properties.getUploadOperation().getDeadlineSeconds();
    }

    private int leaseSeconds() {
        return properties.getUploadOperation().getLeaseSeconds();
    }

    private String hashIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > 128 || !key.matches("[\\x21-\\x7E]+")) {
            throw BusinessException.of(320, "Idempotency-Key 缺失或非法");
        }
        return sha256Hex(("idem:" + key).getBytes(StandardCharsets.UTF_8));
    }

    private String intentFingerprint(
            String userId, StorageFolderEntity folder, String fileName, String mime, long size, String sha256) {
        String raw = String.join(
                "\u0000",
                userId,
                String.valueOf(folder.getId()),
                String.valueOf(folder.getTargetVersion() == null ? 1L : folder.getTargetVersion()),
                fileName,
                mime,
                String.valueOf(size),
                sha256 == null ? "" : sha256);
        return sha256Hex(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String normalizeFileName(String fileName) {
        String name = fileName.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\r\\n]", "_");
        if (name.isEmpty()) {
            throw new BusinessException("COMMON002", "文件名非法");
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    private String normalizeMime(String contentType) {
        if (contentType == null) {
            return "";
        }
        int semicolon = contentType.indexOf(';');
        String mime = semicolon < 0 ? contentType : contentType.substring(0, semicolon);
        return mime.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String normalizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        String value = sha256.trim().toLowerCase(java.util.Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new BusinessException("COMMON002", "sha256 非法");
        }
        return value;
    }

    private String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1);
    }

    private void requireLogin(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException("STORAGE044");
        }
    }

    private String randomId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String writeJson(String url, String thumbUrl, String remoteId, Integer width, Integer height) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        payload.put("thumbUrl", thumbUrl);
        payload.put("remoteId", remoteId);
        payload.put("width", width);
        payload.put("height", height);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            log.warn("上游结果序列化失败: {}", ex.getMessage());
            return "{}";
        }
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private int countAttempts(String attemptsJson) {
        if (attemptsJson == null || attemptsJson.isBlank()) {
            return 0;
        }
        List<?> values = readList(attemptsJson);
        return values.size();
    }

    private String appendAttempt(String attemptsJson, Long configId) {
        List<Object> values = new ArrayList<>(readList(attemptsJson));
        values.add(configId);
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            return attemptsJson;
        }
    }

    private List<?> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }

    // ==================== 视图 ====================

    private UploadOperationDtos.UploadCreateResp toCreateResp(StorageUploadOperationEntity operation, long maxBytes) {
        boolean canSend = StorageUploadOperationEntity.STATE_CREATED.equals(operation.getState())
                && operation.getIntentExpiresAt() != null
                && operation.getIntentExpiresAt().isAfter(LocalDateTime.now());
        return new UploadOperationDtos.UploadCreateResp(
                operation.getUploadId(),
                operation.getPublicId(),
                operation.getState(),
                canSend,
                contentPath(operation.getUploadId()),
                maxBytes,
                operation.getIntentExpiresAt() == null
                        ? 0L
                        : Math.max(
                                0L,
                                java.time.Duration.between(LocalDateTime.now(), operation.getIntentExpiresAt())
                                        .getSeconds()));
    }

    public UploadOperationDtos.UploadOperationView toView(StorageUploadOperationEntity operation) {
        boolean canSend = StorageUploadOperationEntity.STATE_CREATED.equals(operation.getState())
                && operation.getIntentExpiresAt() != null
                && operation.getIntentExpiresAt().isAfter(LocalDateTime.now());
        return new UploadOperationDtos.UploadOperationView(
                operation.getUploadId(),
                operation.getPublicId(),
                operation.getState(),
                canSend,
                contentPath(operation.getUploadId()),
                properties.getUploadOperation().getMaxContentBytes(),
                operation.getFolderId(),
                operation.getOriginalName(),
                operation.getExpectedMime(),
                operation.getExpectedSize(),
                operation.getFileId() == null ? null : String.valueOf(operation.getFileId()),
                operation.getFailureCode(),
                epochMillis(operation.getIntentExpiresAt()),
                epochMillis(operation.getSendDeadline()),
                operation.getQuotaCharge(),
                operation.getQuotaDay() == null ? null : operation.getQuotaDay().toString());
    }

    private long epochMillis(LocalDateTime value) {
        return value == null
                ? 0L
                : value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private String contentPath(String uploadId) {
        return "/api/v1/all/storage/upload-operations/" + uploadId + "/content";
    }
}
