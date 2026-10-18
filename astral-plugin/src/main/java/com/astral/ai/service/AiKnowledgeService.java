package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 知识库服务：知识源、文档、分片索引与检索。
 *
 * <p>检索采用「关键词倒排 + 位置加权」的轻量实现，不依赖外部向量库：答疑场景的语料是
 * 项目文档、API 契约、插件手册，术语密度高、同义改写少，词面召回已经足够；后续接入
 * Embedding 时只需替换 {@link #rankChunks} 的打分部分，接口形状不变。</p>
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

    /** 检索时最少命中的查询词数 */
    private static final int MIN_HIT_TERMS = 1;

    private final AiKnowledgeSourceMapper sourceMapper;
    private final AiKnowledgeDocumentMapper documentMapper;
    private final AiKnowledgeChunkMapper chunkMapper;
    private final AiKnowledgeIndexJobMapper jobMapper;
    private final AiAgentKnowledgeMapper agentKnowledgeMapper;

    // ==================== 知识源 ====================

    /** 全部知识源 */
    public List<AiKnowledgeSourceEntity> listSources() {
        return sourceMapper.selectList(new LambdaQueryWrapper<AiKnowledgeSourceEntity>()
                .orderByAsc(AiKnowledgeSourceEntity::getId));
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
    public AiKnowledgeSourceEntity createSource(String sourceCode, String name, String sourceType,
                                                String description, String pathPattern, Boolean enabled,
                                                String syncMode, Long operatorId) {
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
    public AiKnowledgeSourceEntity updateSource(Long id, String name, String sourceType, String description,
                                                String pathPattern, Boolean enabled, String syncMode,
                                                Long operatorId) {
        AiKnowledgeSourceEntity entity = requireSource(id);
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        if (sourceType != null && !sourceType.isBlank()) {
            entity.setSourceType(sourceType.toUpperCase(Locale.ROOT));
        }
        if (description != null) {
            entity.setDescription(description);
        }
        if (pathPattern != null) {
            entity.setPathPattern(pathPattern);
        }
        if (enabled != null) {
            entity.setEnabled(enabled);
        }
        if (syncMode != null && !syncMode.isBlank()) {
            entity.setSyncMode(syncMode.toUpperCase(Locale.ROOT));
        }
        entity.setUpdatedBy(operatorId);
        sourceMapper.updateById(entity);
        return entity;
    }

    // ==================== 文档 ====================

    /** 某知识源下的文档 */
    public List<AiKnowledgeDocumentEntity> listDocuments(Long sourceId) {
        return documentMapper.selectList(new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
                .eq(AiKnowledgeDocumentEntity::getSourceId, sourceId)
                .orderByAsc(AiKnowledgeDocumentEntity::getDocKey));
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
    public AiKnowledgeDocumentEntity saveDocument(Long sourceId, String docKey, String title, String path,
                                                  String section, String version, String content,
                                                  Long operatorId) {
        requireSource(sourceId);
        if (content == null || content.isBlank()) {
            throw new BusinessException("AI013", "文档内容不能为空");
        }
        String body = content.length() > MAX_DOCUMENT_CHARS
                ? content.substring(0, MAX_DOCUMENT_CHARS) : content;
        String hash = sha256(body);

        AiKnowledgeDocumentEntity entity = documentMapper.selectOne(
                new LambdaQueryWrapper<AiKnowledgeDocumentEntity>()
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
        chunkMapper.delete(new LambdaQueryWrapper<AiKnowledgeChunkEntity>()
                .eq(AiKnowledgeChunkEntity::getDocumentId, id));
        documentMapper.deleteById(id);
        refreshSourceCounters(entity.getSourceId());
    }

    /**
     * 全量重建索引。
     *
     * @param sourceId 知识源
     * @param operatorId 操作人
     * @return 索引任务
     */
    @Transactional(rollbackFor = Exception.class)
    public AiKnowledgeIndexJobEntity syncSource(Long sourceId, Long operatorId) {
        AiKnowledgeSourceEntity source = requireSource(sourceId);
        AiKnowledgeIndexJobEntity job = new AiKnowledgeIndexJobEntity();
        job.setSourceId(sourceId);
        job.setJobType("FULL");
        job.setStatus(AiConstants.IndexJobStatus.RUNNING);
        job.setTotalCount(0);
        job.setProcessedCount(0);
        job.setFailedCount(0);
        job.setStartedAt(LocalDateTime.now());
        job.setCreatedBy(operatorId);
        jobMapper.insert(job);

        List<AiKnowledgeDocumentEntity> documents = listDocuments(sourceId);
        job.setTotalCount(documents.size());
        int processed = 0;
        int failed = 0;
        List<String> errors = new ArrayList<>();
        for (AiKnowledgeDocumentEntity document : documents) {
            try {
                int chunks = reindexDocument(document);
                document.setChunkCount(chunks);
                document.setStatus(AiConstants.KnowledgeStatus.READY);
                document.setIndexedAt(LocalDateTime.now());
                documentMapper.updateById(document);
                processed++;
            } catch (Exception e) {
                failed++;
                if (errors.size() < 5) {
                    errors.add(document.getDocKey() + ": " + e.getMessage());
                }
                log.warn("[AI] 文档索引失败 doc={} reason={}", document.getDocKey(), e.getMessage());
            }
        }
        job.setProcessedCount(processed);
        job.setFailedCount(failed);
        job.setStatus(failed > 0 && processed == 0
                ? AiConstants.IndexJobStatus.FAILED : AiConstants.IndexJobStatus.SUCCEEDED);
        job.setFinishedAt(LocalDateTime.now());
        job.setErrorSummary(errors.isEmpty() ? null : String.join("; ", errors));
        jobMapper.updateById(job);

        source.setLastSyncTime(LocalDateTime.now());
        source.setStatus(processed > 0 ? AiConstants.KnowledgeStatus.READY
                : AiConstants.KnowledgeStatus.PENDING);
        source.setUpdatedBy(operatorId);
        sourceMapper.updateById(source);
        refreshSourceCounters(sourceId);
        return job;
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
            binding.setPriority(binding.getPriority() == null ? sort : binding.getPriority());
            binding.setRetrievalTopK(binding.getRetrievalTopK() == null ? 6
                    : Math.max(1, Math.min(50, binding.getRetrievalTopK())));
            binding.setCitationPolicy(binding.getCitationPolicy() == null
                    ? "OPTIONAL" : binding.getCitationPolicy().toUpperCase(Locale.ROOT));
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
            double score
    ) {
    }

    /**
     * 按智能体版本的知识绑定检索。
     *
     * @param agentVersionId 智能体版本
     * @param query          查询文本
     * @param fallbackTopK   未配置绑定时的默认召回数
     * @return 命中列表（按分数降序）
     */
    public List<AiCitationHit> search(Long agentVersionId, String query, int fallbackTopK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<AiAgentKnowledgeEntity> bindings = listBindings(agentVersionId);
        if (bindings.isEmpty()) {
            return List.of();
        }
        Set<String> terms = tokenize(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        List<Long> sourceIds = bindings.stream()
                .filter(b -> Boolean.TRUE.equals(b.getEnabled()))
                .map(AiAgentKnowledgeEntity::getKnowledgeSourceId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (sourceIds.isEmpty()) {
            return List.of();
        }
        Map<Long, AiKnowledgeSourceEntity> sources = new LinkedHashMap<>();
        for (Long id : sourceIds) {
            AiKnowledgeSourceEntity source = findSource(id);
            if (source != null && Boolean.TRUE.equals(source.getEnabled())) {
                sources.put(id, source);
            }
        }
        if (sources.isEmpty()) {
            return List.of();
        }

        List<AiKnowledgeChunkEntity> chunks = chunkMapper.selectList(
                new LambdaQueryWrapper<AiKnowledgeChunkEntity>()
                        .in(AiKnowledgeChunkEntity::getSourceId, sources.keySet()));
        if (chunks.isEmpty()) {
            return List.of();
        }

        int topK = Math.max(1, Math.min(50, fallbackTopK));
        List<AiCitationHit> hits = rankChunks(chunks, terms, sources);
        // 按智能体配置的 topK 裁剪（不同知识源可配不同条数，这里取最大值作为总上限）
        int configured = bindings.stream()
                .filter(b -> Boolean.TRUE.equals(b.getEnabled()))
                .map(AiAgentKnowledgeEntity::getRetrievalTopK)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(topK);
        int limit = Math.min(AiConstants.Runtime.MAX_CITATIONS, configured * Math.max(1, sources.size()));
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    /** 打分：词面命中 + 标题加权 + 覆盖率，取每条分片最高分 */
    private List<AiCitationHit> rankChunks(List<AiKnowledgeChunkEntity> chunks, Set<String> terms,
                                           Map<Long, AiKnowledgeSourceEntity> sources) {
        Map<Long, AiKnowledgeDocumentEntity> cache = new LinkedHashMap<>();
        List<AiCitationHit> hits = new ArrayList<>();
        for (AiKnowledgeChunkEntity chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            String lower = content.toLowerCase(Locale.ROOT);
            int hitTerms = 0;
            int occurrences = 0;
            String firstTerm = null;
            for (String term : terms) {
                int count = countOccurrences(lower, term);
                if (count > 0) {
                    hitTerms++;
                    occurrences += count;
                    if (firstTerm == null) {
                        firstTerm = term;
                    }
                }
            }
            if (hitTerms < MIN_HIT_TERMS) {
                continue;
            }
            AiKnowledgeDocumentEntity document = cache.computeIfAbsent(chunk.getDocumentId(), id -> {
                AiKnowledgeDocumentEntity doc = id == null ? null : documentMapper.selectById(id);
                return doc;
            });
            if (document == null) {
                continue;
            }
            double coverage = (double) hitTerms / terms.size();
            double score = coverage * 100.0 + Math.min(occurrences, 20) * 1.5;
            String title = document.getTitle() == null ? "" : document.getTitle().toLowerCase(Locale.ROOT);
            if (firstTerm != null && title.contains(firstTerm)) {
                score += 12;
            }
            if (document.getSection() != null && chunk.getSection() != null) {
                score += 1;
            }
            AiKnowledgeSourceEntity source = sources.get(chunk.getSourceId());
            hits.add(new AiCitationHit(
                    document.getId(),
                    chunk.getSourceId(),
                    source == null ? null : source.getSourceType(),
                    document.getTitle(),
                    document.getPath(),
                    chunk.getSection() == null ? document.getSection() : chunk.getSection(),
                    document.getVersion(),
                    chunk.getChunkNo(),
                    snippet(content, firstTerm),
                    content,
                    score
            ));
        }
        hits.sort(Comparator.comparingDouble(AiCitationHit::score).reversed());
        return hits;
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
        Long chunks = chunkMapper.selectCount(new LambdaQueryWrapper<AiKnowledgeChunkEntity>()
                .eq(AiKnowledgeChunkEntity::getSourceId, sourceId));
        AiKnowledgeSourceEntity source = findSource(sourceId);
        if (source == null) {
            return;
        }
        source.setDocumentCount(documents == null ? 0 : documents.intValue());
        sourceMapper.updateById(source);
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

    private static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
            if (count > 50) {
                break;
            }
        }
        return count;
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
        return new BigDecimal("30");
    }
}
