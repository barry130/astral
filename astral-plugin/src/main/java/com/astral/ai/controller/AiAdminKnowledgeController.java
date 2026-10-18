package com.astral.ai.controller;

import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentKnowledgeEntity;
import com.astral.ai.entity.AiKnowledgeDocumentEntity;
import com.astral.ai.entity.AiKnowledgeIndexJobEntity;
import com.astral.ai.entity.AiKnowledgeSourceEntity;
import com.astral.ai.service.AiKnowledgeService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.log.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 后台：知识源、文档与索引任务。
 *
 * <p>入库即建分片，所以「文档保存成功」就等于「已可检索」。{@code /sync} 只做一次全量重扫，
 * 用于批量导入后统一刷新状态，不参与在线问答的实时路径。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:config:view", name = "AI 配置查看", description = "AI 知识库查看")
@RequiredArgsConstructor
public class AiAdminKnowledgeController {

    private final AiKnowledgeService knowledgeService;

    private static Long operatorId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            return null;
        }
        try {
            return Long.valueOf(userId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 知识源 ====================

    @GetMapping("/knowledge/sources")
    @RequiresPermission(value = "admin:ai:knowledge:view", name = "AI 知识库查看", description = "查看知识源")
    public Result<List<AiAdminDtos.KnowledgeSourceVo>> sources() {
        List<AiAdminDtos.KnowledgeSourceVo> list = new ArrayList<>();
        for (AiKnowledgeSourceEntity entity : knowledgeService.listSources()) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @GetMapping("/knowledge/sources/{id}")
    @RequiresPermission(value = "admin:ai:knowledge:view", name = "AI 知识库查看", description = "查看知识源")
    public Result<AiAdminDtos.KnowledgeSourceVo> source(@PathVariable Long id) {
        return Result.success(toVo(knowledgeService.requireSource(id)));
    }

    @PostMapping("/knowledge/sources")
    @RequiresPermission(value = "admin:ai:knowledge:edit", name = "AI 知识库编辑", description = "新增知识源")
    @OperateLog("新增 AI 知识源")
    public Result<AiAdminDtos.KnowledgeSourceVo> createSource(
            @Valid @RequestBody AiAdminDtos.KnowledgeSourceSaveReq req, HttpServletRequest request) {
        AiKnowledgeSourceEntity entity = knowledgeService.createSource(req.sourceCode(), req.name(),
                req.sourceType(), req.description(), req.pathPattern(), req.enabled(), req.syncMode(),
                operatorId(request));
        return Result.success("创建成功", toVo(entity));
    }

    @PutMapping("/knowledge/sources/{id}")
    @RequiresPermission(value = "admin:ai:knowledge:edit", name = "AI 知识库编辑", description = "修改知识源")
    @OperateLog("修改 AI 知识源")
    public Result<AiAdminDtos.KnowledgeSourceVo> updateSource(
            @PathVariable Long id,
            @Valid @RequestBody AiAdminDtos.KnowledgeSourceSaveReq req,
            HttpServletRequest request) {
        AiKnowledgeSourceEntity entity = knowledgeService.updateSource(id, req.name(), req.sourceType(),
                req.description(), req.pathPattern(), req.enabled(), req.syncMode(), operatorId(request));
        return Result.success("保存成功", toVo(entity));
    }

    // ==================== 文档 ====================

    @GetMapping("/knowledge/sources/{id}/documents")
    @RequiresPermission(value = "admin:ai:knowledge:view", name = "AI 知识库查看", description = "查看知识文档")
    public Result<List<AiAdminDtos.KnowledgeDocumentVo>> documents(@PathVariable Long id) {
        knowledgeService.requireSource(id);
        List<AiAdminDtos.KnowledgeDocumentVo> list = new ArrayList<>();
        for (AiKnowledgeDocumentEntity entity : knowledgeService.listDocuments(id)) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @PostMapping("/knowledge/sources/{id}/documents")
    @RequiresPermission(value = "admin:ai:knowledge:edit", name = "AI 知识库编辑", description = "保存知识文档")
    @OperateLog("保存 AI 知识文档")
    public Result<AiAdminDtos.KnowledgeDocumentVo> saveDocument(
            @PathVariable Long id,
            @Valid @RequestBody AiAdminDtos.KnowledgeDocumentSaveReq req,
            HttpServletRequest request) {
        AiKnowledgeDocumentEntity entity = knowledgeService.saveDocument(id, req.docKey(), req.title(),
                req.path(), req.section(), req.version(), req.content(), operatorId(request));
        return Result.success("已保存并完成索引", toVo(entity));
    }

    @DeleteMapping("/knowledge/documents/{id}")
    @RequiresPermission(value = "admin:ai:knowledge:edit", name = "AI 知识库编辑", description = "删除知识文档")
    @OperateLog("删除 AI 知识文档")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        knowledgeService.deleteDocument(id);
        return Result.success("已删除", null);
    }

    @PostMapping("/knowledge/sources/{id}/sync")
    @RequiresPermission(value = "admin:ai:knowledge:sync", name = "AI 知识库同步", description = "触发知识源重建索引")
    @OperateLog("重建 AI 知识源索引")
    public Result<AiAdminDtos.IndexJobVo> sync(@PathVariable Long id, HttpServletRequest request) {
        return Result.success("已提交", toVo(knowledgeService.syncSource(id, operatorId(request))));
    }

    @GetMapping("/knowledge/index-jobs/{jobId}")
    @RequiresPermission(value = "admin:ai:knowledge:view", name = "AI 知识库查看", description = "查看索引任务")
    public Result<AiAdminDtos.IndexJobVo> indexJob(@PathVariable Long jobId) {
        return Result.success(toVo(knowledgeService.requireJob(jobId)));
    }

    // ==================== 转换 ====================

    private AiAdminDtos.KnowledgeSourceVo toVo(AiKnowledgeSourceEntity entity) {
        int chunks = 0;
        for (AiKnowledgeDocumentEntity document : knowledgeService.listDocuments(entity.getId())) {
            chunks += document.getChunkCount() == null ? 0 : document.getChunkCount();
        }
        return new AiAdminDtos.KnowledgeSourceVo(entity.getId(), entity.getSourceCode(), entity.getName(),
                entity.getSourceType(), entity.getDescription(), entity.getPathPattern(), entity.getEnabled(),
                entity.getSyncMode(), entity.getStatus(), entity.getLastSyncTime(),
                entity.getDocumentCount(), chunks, entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiAdminDtos.KnowledgeDocumentVo toVo(AiKnowledgeDocumentEntity entity) {
        return new AiAdminDtos.KnowledgeDocumentVo(entity.getId(), entity.getSourceId(), entity.getDocKey(),
                entity.getTitle(), entity.getPath(), entity.getSection(), entity.getVersion(),
                entity.getContentHash(), entity.getStatus(), entity.getChunkCount(), entity.getIndexedAt(),
                entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiAdminDtos.IndexJobVo toVo(AiKnowledgeIndexJobEntity entity) {
        return new AiAdminDtos.IndexJobVo(entity.getId(), entity.getSourceId(), entity.getJobType(),
                entity.getStatus(), entity.getTotalCount(), entity.getProcessedCount(), entity.getFailedCount(),
                entity.getStartedAt(), entity.getFinishedAt(), entity.getErrorSummary());
    }

    /** 供智能体版本引用校验时复用 */
    static List<Long> sourceIds(List<AiAgentKnowledgeEntity> bindings) {
        List<Long> ids = new ArrayList<>();
        for (AiAgentKnowledgeEntity binding : bindings) {
            ids.add(binding.getKnowledgeSourceId());
        }
        return ids;
    }
}
