package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.entity.AiKnowledgeChunkEntity;
import com.astral.ai.entity.AiKnowledgeDocumentEntity;
import com.astral.ai.entity.AiKnowledgeIndexJobEntity;
import com.astral.ai.entity.AiKnowledgeSourceEntity;
import com.astral.ai.mapper.AiAgentKnowledgeMapper;
import com.astral.ai.mapper.AiKnowledgeChunkMapper;
import com.astral.ai.mapper.AiKnowledgeDocumentMapper;
import com.astral.ai.mapper.AiKnowledgeIndexJobMapper;
import com.astral.ai.mapper.AiKnowledgeSourceMapper;
import com.astral.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库服务：知识源、文档、分片索引与检索。
 *
 * <p>检索采用「关键词倒排 + 位置加权」的轻量实现，不依赖外部向量库：答疑场景的语料是
 * 项目文档、API 契约、插件手册，术语密度高、同义改写少，词面召回已经足够；数据库索引筛选候选、精确子串打分并限制返回条数，查询预算为 3 秒。</p>
 *
 * <p>所有引用都必须能回溯到具体的文档 + 章节，{@code AiCitationHit} 里带全
 * {@code sourceId / sourceType / title / path / section / version / matchedText}，
 * 答卷侧不允许自行编造引用（设计文档 §4.9）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiKnowledgeService {

    /** 单个分片的目标字符数 */
    private static final int CHUNK_SIZE = 700;

    /** 相邻分片的重叠字符数（避免跨段切开语义） */
    private static final int CHUNK_OVERLAP = 120;

    /** 单文档最大入库字符数，防止误贴大文件撑爆索引 */
    private static final int MAX_DOCUMENT_CHARS = 400_000;

    private final AiKnowledgeSourceMapper sourceMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeChunkMapper chunkMapper;
    private final AiKnowledgeIndexJobMapper jobMapper;
    private final AiAgentKnowledgeMapper agentKnowledgeMapper;
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    // ==================== 知识源 ====================

    /** 全部知识源 */
    public List<AiKnowledgeSourceEntity> listSources() {
        return sourceMapper.selectList(
                new LambdaQueryWrapper<AiKnowledgeSourceEntity>().orderByAsc(AiKnowledgeSourceEntity::getId));
    }

    /** 取知识源 */
    public AiKnowledgeSourceEntity findSource(Long id) {
        return id == null ? null : sourceMapper.selectById(id);
    }

    /** 取知识源，不存在抛错 */
    public AiKnowledgeSourceEntity requireSource(Long id) {
        AiKnowledgeSourceEntity entity = findSource(id);
        if (entity == null) {
            throw new BusinessException("AI013", "知识源不存在: " + id);
        }
        return entity;
    }

    /** 新增知识源 */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeSourceEntity createSource(
            String sourceCode,
            String name,
            String sourceType,
            String description,
            String pathPattern,
            Boolean enabled,
            String syncMode,
            Long operatorId) {
        Long exists = sourceMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeSourceEntity>()
                .eq(AiKnowledgeSourceEntity::getSourceCode, sourceCode));
        if (exists != null && exists > 0) {
            throw new BusinessException("AI013", "知识源编码已存在: " + sourceCode);
        }
        AiKnowledgeSourceEntity entity = new AiKnowledgeSourceEntity();
        entity.setSourceCode(sourceCode);
        entity.setName(name);
        entity.setSourceType(sourceType == null ? "PROJECT_DOCUMENT" : sourceType.toUpperCase(Locale.ROOT));
        entity.setDescription(description);
        entity.setPathPattern(pathPattern);
        entity.setEnabled(enabled == null || enabled);
        entity.setSyncMode(syncMode == null || syncMode.isBlank() ? "MANUAL" : syncMode.toUpperCase(Locale.ROOT));
        entity.setStatus(AiConstants.KnowledgeStatus.PENDING);
        entity.setDocumentCount(0);
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        sourceMapper.insert(entity);
        return entity;
    }

    /** 更新知识源 */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeSourceEntity updateSource(
            Long id,
            String name,
            String sourceType,
            String description,
            String pathPattern,
            Boolean enabled,
            String syncMode,
            Long operatorId) {
        requireSource(id);
        var patch = new LambdaUpdateWrapper<AiKnowledgeSourceEntity>().eq(AiKnowledgeSourceEntity::getId, id);
        if (name != null && !name.isBlank()) {
            patch.set(AiKnowledgeSourceEntity::getName, name.trim());
        }
        if (sourceType != null && !sourceType.isBlank()) {
            patch.set(AiKnowledgeSourceEntity::getSourceType, sourceType.toUpperCase(Locale.ROOT));
        }
        if (description != null) {
            patch.set(AiKnowledgeSourceEntity::getDescription, description);
        }
        if (pathPattern != null) {
            patch.set(AiKnowledgeSourceEntity::getPathPattern, pathPattern);
        }
        if (enabled != null) {
            patch.set(AiKnowledgeSourceEntity::getEnabled, enabled);
        }
        if (syncMode != null && !syncMode.isBlank()) {
            patch.set(AiKnowledgeSourceEntity::getSyncMode, syncMode.toUpperCase(Locale.ROOT));
        }
        patch.set(AiKnowledgeSourceEntity::getUpdatedBy, operatorId)
                .set(AiKnowledgeSourceEntity::getUpdateTime, LocalDateTime.now());
        sourceMapper.update(null, patch);
        return requireSource(id);
    }

    // ==================== 文档 ====================

    /** Bounded metadata page. Bodies are loaded only through the document-detail endpoint. */
    public Page<AiKnowledgeDocumentEntity> listDocuments(Long sourceId, int pageNum, int pageSize) {
        var query = new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
                .select(
                        AiKnowledgeDocumentEntity.class,
                        field -> !field.getProperty().equals("content"))
                .eq(AiKnowledgeDocumentEntity::getSourceId, sourceId)
                .orderByAsc(AiKnowledgeDocumentEntity::getDocKey)
                .orderByAsc(AiKnowledgeDocumentEntity::getId);
        return documentMapper.selectPage(new Page<>(Math.max(1, pageNum), Math.clamp(pageSize, 1, 100)), query);
    }

    public Map<Long, Long> chunkCounts() {
        Map<Long, Long> counts = new LinkedHashMap<>();
        documentMapper.countChunksBySource().forEach(row -> counts.put(row.sourceId(), row.chunks()));
        return counts;
    }

    /** 取文档 */
    public AiKnowledgeDocumentEntity requireDocument(Long id) {
        AiKnowledgeDocumentEntity entity = id == null ? null : documentMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("AI013", "知识文档不存在: " + id);
        }
        return entity;
    }

    /**
     * 新增或更新文档（按 sourceId + docKey 幂等），并立即重建该文档的分片。
     *
     * @return 文档实体
     */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeDocumentEntity saveDocument(
            Long sourceId,
            String docKey,
            String title,
            String path,
            String section,
            String version,
            String content,
            Long operatorId) {
        requireSource(sourceId);
        if (content == null || content.isBlank()) {
            throw new BusinessException("AI013", "文档内容不能为空");
        }
        if (content.length() > MAX_DOCUMENT_CHARS) throw new BusinessException("AI013", "文档最多 400000 个字符，请拆分后保存");
        documentMapper.lockSource(sourceId);
        String body = content;
        String hash = sha256(body);

        AiKnowledgeDocumentEntity entity = documentMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
                .eq(AiKnowledgeDocumentEntity::getSourceId, sourceId)
                .eq(AiKnowledgeDocumentEntity::getDocKey, docKey)
                .last("limit 1"));
        boolean isNew = entity == null;
        if (isNew) {
            entity = new AiKnowledgeDocumentEntity();
            entity.setSourceId(sourceId);
            entity.setDocKey(docKey);
        }
        entity.setTitle(title);
        entity.setPath(path);
        entity.setSection(section);
        entity.setVersion(version);
        entity.setContent(body);
        entity.setContentHash(hash);
        entity.setStatus(AiConstants.KnowledgeStatus.READY);
        entity.setIndexedAt(LocalDateTime.now());
        if (isNew) {
            documentMapper.insert(entity);
        } else {
            documentMapper.updateById(entity);
        }

        int chunks = reindexDocument(entity);
        entity.setChunkCount(chunks);
        documentMapper.updateById(entity);
        refreshSourceCounters(sourceId);
        return entity;
    }

    /** 删除文档及其分片 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteDocument(Long id) {
        AiKnowledgeDocumentEntity entity = requireDocument(id);
        documentMapper.lockSource(entity.getSourceId());
        entity = requireDocument(id);
        chunkMapper.delete(
                new LambdaQueryWrapper<AiKnowledgeChunkEntity>().eq(AiKnowledgeChunkEntity::getDocumentId, id));
        documentMapper.deleteById(id);
        refreshSourceCounters(entity.getSourceId());
    }

    /** Submit a durable rebuild. Repeated clicks return the source's current active job. */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeIndexJobEntity syncSource(Long sourceId, Long operatorId) {
        requireSource(sourceId);
        documentMapper.lockSource(sourceId);
        var existing = jobMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeIndexJobEntity>()
                .eq(AiKnowledgeIndexJobEntity::getSourceId, sourceId)
                .in(AiKnowledgeIndexJobEntity::getStatus, "PENDING", "RUNNING")
                .orderByDesc(AiKnowledgeIndexJobEntity::getId)
                .last("LIMIT 1"));
        if (existing != null) return existing;
        var job = new AiKnowledgeIndexJobEntity();
        job.setSourceId(sourceId);
        job.setJobType("FULL");
        job.setStatus(AiConstants.IndexJobStatus.PENDING);
        job.setTotalCount(Math.toIntExact(documentMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
                .eq(AiKnowledgeDocumentEntity::getSourceId, sourceId))));
        var last = documentMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
                .select(AiKnowledgeDocumentEntity::getId)
                .eq(AiKnowledgeDocumentEntity::getSourceId, sourceId)
                .orderByDesc(AiKnowledgeDocumentEntity::getId)
                .last("LIMIT 1"));
        job.setEndDocumentId(last == null ? 0L : last.getId());
        job.setLastDocumentId(0L);
        job.setProcessedCount(0);
        job.setFailedCount(0);
        job.setCreatedBy(operatorId);
        jobMapper.insert(job);
        return job;
    }

    public AiKnowledgeIndexJobEntity latestJob(Long sourceId) {
        requireSource(sourceId);
        return jobMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeIndexJobEntity>()
                .eq(AiKnowledgeIndexJobEntity::getSourceId, sourceId)
                .orderByDesc(AiKnowledgeIndexJobEntity::getId)
                .last("LIMIT 1"));
    }

    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeIndexJobEntity cancelJob(Long jobId) {
        requireJob(jobId);
        jobMapper.update(
                null,
                new LambdaUpdateWrapper<AiKnowledgeIndexJobEntity>()
                        .eq(AiKnowledgeIndexJobEntity::getId, jobId)
                        .in(AiKnowledgeIndexJobEntity::getStatus, "PENDING", "RUNNING")
                        .set(AiKnowledgeIndexJobEntity::getStatus, AiConstants.IndexJobStatus.CANCELLED)
                        .set(AiKnowledgeIndexJobEntity::getFinishedAt, LocalDateTime.now())
                        .set(AiKnowledgeIndexJobEntity::getUpdateTime, LocalDateTime.now()));
        return requireJob(jobId);
    }

    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeIndexJobEntity retryJob(Long jobId, Long operatorId) {
        var previous = requireJob(jobId);
        if (!List.of("FAILED", "CANCELLED").contains(previous.getStatus())) {
            throw new BusinessException("AI013", "只能重试失败或已取消的任务");
        }
        return syncSource(previous.getSourceId(), operatorId);
    }

    /** One document per transaction, with a durable cursor and row lock shared by all instances. */
    public boolean processNextIndexDocument() {
        var transaction = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        transaction.setTimeout(30);
        var claimed = new java.util.concurrent.atomic.AtomicReference<Long>();
        try {
            return Boolean.TRUE.equals(transaction.execute(status -> {
                var job = jobMapper.selectOne(new LambdaQueryWrapper<AiKnowledgeIndexJobEntity>()
                        .in(AiKnowledgeIndexJobEntity::getStatus, "PENDING", "RUNNING")
                        .orderByAsc(AiKnowledgeIndexJobEntity::getId)
                        .last("LIMIT 1 FOR UPDATE SKIP LOCKED"));
                if (job == null) return false;
                claimed.set(job.getId());
                documentMapper.lockSource(job.getSourceId());
                if (job.getStartedAt() == null) job.setStartedAt(LocalDateTime.now());
                job.setStatus(AiConstants.IndexJobStatus.RUNNING);
                var documents = documentMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
                        .eq(AiKnowledgeDocumentEntity::getSourceId, job.getSourceId())
                        .gt(AiKnowledgeDocumentEntity::getId, job.getLastDocumentId())
                        .le(AiKnowledgeDocumentEntity::getId, job.getEndDocumentId())
                        .orderByAsc(AiKnowledgeDocumentEntity::getId)
                        .last("LIMIT 1"));
                if (documents.isEmpty()) {
                    job.setStatus(AiConstants.IndexJobStatus.SUCCEEDED);
                    job.setFinishedAt(LocalDateTime.now());
                    job.setTotalCount(job.getProcessedCount());
                    sourceMapper.update(
                            null,
                            new LambdaUpdateWrapper<AiKnowledgeSourceEntity>()
                                    .eq(AiKnowledgeSourceEntity::getId, job.getSourceId())
                                    .set(AiKnowledgeSourceEntity::getLastSyncTime, LocalDateTime.now())
                                    .set(
                                            AiKnowledgeSourceEntity::getStatus,
                                            job.getProcessedCount() > 0
                                                    ? AiConstants.KnowledgeStatus.READY
                                                    : AiConstants.KnowledgeStatus.PENDING)
                                    .set(AiKnowledgeSourceEntity::getUpdatedBy, job.getCreatedBy())
                                    .set(AiKnowledgeSourceEntity::getUpdateTime, LocalDateTime.now()));
                    refreshSourceCounters(job.getSourceId());
                } else {
                    var document = documents.getFirst();
                    int chunks = reindexDocument(document);
                    documentMapper.update(
                            null,
                            new LambdaUpdateWrapper<AiKnowledgeDocumentEntity>()
                                    .eq(AiKnowledgeDocumentEntity::getId, document.getId())
                                    .set(AiKnowledgeDocumentEntity::getChunkCount, chunks)
                                    .set(AiKnowledgeDocumentEntity::getStatus, AiConstants.KnowledgeStatus.READY)
                                    .set(AiKnowledgeDocumentEntity::getIndexedAt, LocalDateTime.now()));
                    job.setLastDocumentId(document.getId());
                    job.setProcessedCount(job.getProcessedCount() + 1);
                }
                job.setUpdateTime(LocalDateTime.now());
                jobMapper.updateById(job);
                return true;
            }));
        } catch (RuntimeException failure) {
            if (claimed.get() == null) throw failure;
            // The failed document transaction has rolled back. Keep a terminal job users can inspect and retry.
            transaction.executeWithoutResult(status -> jobMapper.update(
                    null,
                    new LambdaUpdateWrapper<AiKnowledgeIndexJobEntity>()
                            .eq(AiKnowledgeIndexJobEntity::getId, claimed.get())
                            .in(AiKnowledgeIndexJobEntity::getStatus, "PENDING", "RUNNING")
                            .set(AiKnowledgeIndexJobEntity::getStatus, AiConstants.IndexJobStatus.FAILED)
                            .set(AiKnowledgeIndexJobEntity::getFailedCount, 1)
                            .set(AiKnowledgeIndexJobEntity::getErrorSummary, "文档索引失败，请重试；持续失败请联系管理员查看日志")
                            .set(AiKnowledgeIndexJobEntity::getFinishedAt, LocalDateTime.now())
                            .set(AiKnowledgeIndexJobEntity::getUpdateTime, LocalDateTime.now())));
            log.warn("[AI] 知识索引失败 job={}", claimed.get(), failure);
            return true;
        }
    }

    /** 取索引任务 */
    public AiKnowledgeIndexJobEntity requireJob(Long id) {
        AiKnowledgeIndexJobEntity job = id == null ? null : jobMapper.selectById(id);
        if (job == null) {
            throw new BusinessException("AI013", "索引任务不存在: " + id);
        }
        return job;
    }

    // ==================== 智能体绑定 ====================

    /** 某智能体版本绑定的知识源 */
    public List<AiAgentKnowledgeEntity> listBindings(Long agentVersionId) {
        return agentKnowledgeMapper.selectList(new LambdaQueryWrapper<AiAgentKnowledgeEntity>()
                .eq(AiAgentKnowledgeEntity::getAgentVersionId, agentVersionId)
                .orderByAsc(AiAgentKnowledgeEntity::getPriority));
    }

    /** 整体替换某智能体版本的知识源绑定 */
    @Transactional(rollbackFor = Exception.class)
    public void replaceBindings(Long agentVersionId, List<AiAgentKnowledgeEntity> bindings) {
        if (!AiConstants.VersionStatus.DRAFT.equals(agentKnowledgeMapper.lockVersionStatus(agentVersionId))) {
            throw new BusinessException("AI007", "只能修改现有草稿版本的知识绑定");
        }
        if (bindings != null) {
            for (var binding : bindings) {
                BigDecimal minimum = binding.getMinScore();
                if (minimum != null && (minimum.signum() < 0 || minimum.compareTo(BigDecimal.ONE) > 0))
                    throw new BusinessException("AI013", "最低相关度须在 0 到 1 之间");
            }
        }
        agentKnowledgeMapper.delete(new LambdaQueryWrapper<AiAgentKnowledgeEntity>()
                .eq(AiAgentKnowledgeEntity::getAgentVersionId, agentVersionId));
        if (bindings == null) {
            return;
        }
        int sort = 0;
        for (AiAgentKnowledgeEntity binding : bindings) {
            if (binding.getKnowledgeSourceId() == null) {
                continue;
            }
            requireSource(binding.getKnowledgeSourceId());
            binding.setId(null);
            binding.setAgentVersionId(agentVersionId);
            binding.setEnabled(binding.getEnabled() == null || binding.getEnabled());
            binding.setMinScore(binding.getMinScore() == null ? defaultMinScore() : binding.getMinScore());
            binding.setScoreScale("NORMALIZED_V1");
            binding.setPriority(binding.getPriority() == null ? sort : binding.getPriority());
            binding.setRetrievalTopK(
                    binding.getRetrievalTopK() == null ? 6 : Math.max(1, Math.min(50, binding.getRetrievalTopK())));
            binding.setCitationPolicy(
                    binding.getCitationPolicy() == null
                            ? "OPTIONAL"
                            : binding.getCitationPolicy().toUpperCase(Locale.ROOT));
            agentKnowledgeMapper.insert(binding);
            sort++;
        }
    }

    // ==================== 检索 ====================

    /**
     * 检索命中。
     *
     * @param documentId  文档 ID
     * @param sourceId    知识源 ID
     * @param sourceType  来源类型
     * @param title       文档标题
     * @param path        文档路径
     * @param section     章节
     * @param version     文档版本
     * @param chunkNo     分片序号
     * @param matchedText 命中片段（截断）
     * @param content     分片正文（拼上下文用）
     * @param score       相关度
     */
    public record AiCitationHit(
            Long documentId,
            Long sourceId,
            String sourceType,
            String title,
            String path,
            String section,
            String version,
            Integer chunkNo,
            String matchedText,
            String content,
            double score) {}

    /**
     * 按智能体版本的知识绑定检索。
     *
     * @param agentVersionId 智能体版本
     * @param query          查询文本
     * @param fallbackTopK   未配置绑定时的默认召回数
     * @return 命中列表（按分数降序）
     */
    public List<AiCitationHit> search(Long agentVersionId, String query, int fallbackTopK) {
        if (query == null || query.isBlank()) return List.of();
        Set<String> terms = tokenize(query.substring(0, Math.min(query.length(), 12000)));
        if (terms.isEmpty()
                || listBindings(agentVersionId).stream().noneMatch(b -> Boolean.TRUE.equals(b.getEnabled())))
            return List.of();
        try {
            return chunkMapper
                    .search(
                            agentVersionId,
                            List.copyOf(terms),
                            Math.clamp(fallbackTopK, 1, 50),
                            AiConstants.Runtime.MAX_CITATIONS)
                    .stream()
                    .map(hit -> new AiCitationHit(
                            hit.getDocumentId(),
                            hit.getSourceId(),
                            hit.getSourceType(),
                            hit.getTitle(),
                            hit.getPath(),
                            hit.getSection(),
                            hit.getVersion(),
                            hit.getChunkNo(),
                            snippet(hit.getContent(), hit.getFirstTerm()),
                            hit.getContent(),
                            hit.getScore()))
                    .toList();
        } catch (org.springframework.dao.QueryTimeoutException timeout) {
            throw new BusinessException("AI013", "知识检索超时，请缩小查询范围后重试");
        }
    }

    // ==================== 分片 ====================

    /** 重建单个文档的分片，返回分片数 */
    private int reindexDocument(AiKnowledgeDocumentEntity document) {
        chunkMapper.delete(new LambdaQueryWrapper<AiKnowledgeChunkEntity>()
                .eq(AiKnowledgeChunkEntity::getDocumentId, document.getId()));
        if (document.getContent() == null || document.getContent().isBlank()) {
            return 0;
        }
        List<String> pieces = split(document.getContent());
        int no = 0;
        for (String piece : pieces) {
            if (piece.isBlank()) {
                continue;
            }
            AiKnowledgeChunkEntity chunk = new AiKnowledgeChunkEntity();
            chunk.setDocumentId(document.getId());
            chunk.setSourceId(document.getSourceId());
            chunk.setChunkNo(no);
            chunk.setSection(document.getSection());
            chunk.setContent(piece);
            chunk.setTokenCount(estimateTokens(piece));
            chunkMapper.insert(chunk);
            no++;
        }
        return no;
    }

    /**
     * 按空行与标题切成段落，再按目标长度合并。
     *
     * <p>先按 markdown 标题/空行断句，保证不会把两个章节混进同一个分片；
     * 再对超长段落做定长滑窗。</p>
     */
    List<String> split(String content) {
        List<String> result = new ArrayList<>();
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        String[] blocks = normalized.split("\n{2,}");
        StringBuilder buffer = new StringBuilder();
        for (String block : blocks) {
            String trimmed = block.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() > CHUNK_SIZE) {
                if (!buffer.isEmpty()) {
                    result.add(buffer.toString().strip());
                    buffer.setLength(0);
                }
                int start = 0;
                while (start < trimmed.length()) {
                    int end = Math.min(trimmed.length(), start + CHUNK_SIZE);
                    result.add(trimmed.substring(start, end).strip());
                    if (end >= trimmed.length()) {
                        break;
                    }
                    start = Math.max(end - CHUNK_OVERLAP, start + 1);
                }
                continue;
            }
            if (buffer.length() + trimmed.length() + 2 > CHUNK_SIZE && !buffer.isEmpty()) {
                result.add(buffer.toString().strip());
                buffer.setLength(0);
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
            }
            buffer.append(trimmed);
        }
        if (!buffer.isEmpty()) {
            result.add(buffer.toString().strip());
        }
        return result;
    }

    /** 统计并回写知识源的文档数与分片数 */
    private void refreshSourceCounters(Long sourceId) {
        Long documents = documentMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
                .eq(AiKnowledgeDocumentEntity::getSourceId, sourceId));
        Long chunks = chunkMapper.selectCount(
                new LambdaQueryWrapper<AiKnowledgeChunkEntity>().eq(AiKnowledgeChunkEntity::getSourceId, sourceId));
        AiKnowledgeSourceEntity source = findSource(sourceId);
        if (source == null) {
            return;
        }
        sourceMapper.update(
                null,
                new LambdaUpdateWrapper<AiKnowledgeSourceEntity>()
                        .eq(AiKnowledgeSourceEntity::getId, sourceId)
                        .set(AiKnowledgeSourceEntity::getDocumentCount, documents == null ? 0 : documents.intValue())
                        .set(
                                AiKnowledgeSourceEntity::getStatus,
                                documents != null && documents > 0
                                        ? AiConstants.KnowledgeStatus.READY
                                        : AiConstants.KnowledgeStatus.PENDING));
        log.debug("[AI] 知识源计数 source={} docs={} chunks={}", sourceId, documents, chunks);
    }

    /** 命中片段截断 */
    private static String snippet(String content, String term) {
        int max = AiConstants.Runtime.MAX_MATCHED_TEXT;
        String text = content.replaceAll("\\s+", " ").strip();
        if (term != null) {
            int at = text.toLowerCase(Locale.ROOT).indexOf(term);
            if (at > 60) {
                int from = Math.max(0, at - 60);
                text = "…" + text.substring(from);
            }
        }
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /** 粗略分词：英文/数字按段，中文按 2-gram（够用且无需外部分词器） */
    static Set<String> tokenize(String query) {
        Set<String> terms = new LinkedHashSet<>();
        String lower = query.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) && c < 128) {
                word.append(c);
                if (cjk.length() > 0) {
                    addCjkTerms(cjk, terms);
                    cjk.setLength(0);
                }
            } else if (isCjk(c)) {
                if (word.length() > 1) {
                    terms.add(word.toString());
                }
                word.setLength(0);
                cjk.append(c);
            } else {
                if (word.length() > 1) {
                    terms.add(word.toString());
                }
                word.setLength(0);
                if (cjk.length() > 0) {
                    addCjkTerms(cjk, terms);
                    cjk.setLength(0);
                }
            }
        }
        if (word.length() > 1) {
            terms.add(word.toString());
        }
        if (cjk.length() > 0) {
            addCjkTerms(cjk, terms);
        }
        // 去掉过于常见的词，避免全量命中
        terms.removeAll(Set.of("the", "and", "for", "with", "this", "that", "如何", "怎么", "什么", "是否"));
        return terms.size() > 24 ? new LinkedHashSet<>(new ArrayList<>(terms).subList(0, 24)) : terms;
    }

    private static void addCjkTerms(StringBuilder buffer, Set<String> terms) {
        String text = buffer.toString();
        for (int i = 0; i < text.length() - 1; i++) {
            terms.add(text.substring(i, i + 2));
        }
        if (text.length() == 1) {
            terms.add(text);
        }
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    /** 粗估 token 数：中文按 1 字 1 token，英文按 4 字符 1 token */
    static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            }
        }
        int other = text.length() - cjk;
        return cjk + Math.max(1, other / 4);
    }

    /** SHA-256 十六进制 */
    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /** 供答疑侧判断某版本是否已挂知识源 */
    public boolean hasReadyKnowledge(Long agentVersionId) {
        List<AiAgentKnowledgeEntity> bindings = listBindings(agentVersionId);
        return bindings.stream().anyMatch(b -> Boolean.TRUE.equals(b.getEnabled()));
    }

    /** 默认 minScore（未配置时用它过滤噪声命中） */
    public static BigDecimal defaultMinScore() {
        return new BigDecimal("0.2");
    }
}
