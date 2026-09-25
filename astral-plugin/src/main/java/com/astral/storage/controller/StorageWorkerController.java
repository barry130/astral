package com.astral.storage.controller;

import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.mapper.StorageConfigMapper;
import com.astral.storage.service.StorageFileService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * storage 插件 Worker 专属 API（/api/v1/all/storage/worker/** 与 /origin/**）
 * <p>
 * 仅接受 Cloudflare Worker 的 HMAC 服务身份（StorageWorkerAuthInterceptor，STORAGE017），
 * 浏览器 satoken 与公开签名 URL 均不能调用。规范串与 Worker 逐字节一致：
 * METHOD\nPATH\nTIMESTAMP\nNONCE。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/all/storage")
@RequiredArgsConstructor
public class StorageWorkerController {

    private final StorageFileService fileService;
    private final StorageConfigMapper configMapper;
    private final ObjectMapper objectMapper;

    /**
     * 上传结果回调：Worker 完成 sendDocument 后登记元数据（幂等，uploadId 唯一）。
     * 登记后按文件夹策略 verifyContent 执行内容验证（失败删对象置 FAILED 并报错）。
     */
    @PostMapping("/worker/upload-callback")
    public Result<WorkerCallbackResp> uploadCallback(@RequestBody StorageDtos.WorkerCallbackReq req) {
        StorageFileEntity file = fileService.registerFromCallback(req);
        fileService.verifyContentAfterRegistration(file.getPublicId());
        return Result.success(new WorkerCallbackResp(file.getPublicId(), file.getStatus()));
    }

    public record WorkerCallbackResp(String publicId, String status) {
    }

    /**
     * 下载回源元数据：Worker 验签缓存未命中时查询 Telegram 定位信息。
     * contentVersion 不匹配视为已失效（版本化缓存失效）。
     */
    @GetMapping("/origin/files/{publicId}/{contentVersion}")
    public Result<StorageDtos.OriginFileView> originFile(@PathVariable String publicId,
                                                         @PathVariable long contentVersion) {
        StorageFileEntity file = fileService.getByPublicId(publicId);
        if (!StorageFileEntity.STATUS_AVAILABLE.equals(file.getStatus())) {
            throw new BusinessException("STORAGE016");
        }
        if (file.getContentVersion() != contentVersion) {
            // 版本已过期：旧 URL 不得继续回源
            throw new BusinessException("STORAGE016");
        }
        StorageConfigEntity config = configMapper.selectOne(new LambdaQueryWrapper<StorageConfigEntity>()
                .eq(StorageConfigEntity::getId, file.getStorageConfigId())
                .eq(StorageConfigEntity::getStatus, StorageConfigEntity.STATUS_ENABLED)
                .last("LIMIT 1"));
        if (config == null) {
            throw new BusinessException("STORAGE002");
        }
        return Result.success(toOriginView(file));
    }

    /** 拉取待执行的远端删除任务（Worker 定时拉取后执行 deleteMessage） */
    @GetMapping("/worker/tasks/delete")
    public Result<List<StorageDtos.WorkerTaskView>> pullDeleteTasks(
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(fileService.pullDeleteTasks(limit));
    }

    /** 删除任务确认：成功归档文件；失败进入指数退避重试 */
    @PostMapping("/worker/tasks/{taskId}/ack")
    public Result<Void> ackTask(@PathVariable long taskId,
                                @RequestBody StorageDtos.WorkerAckReq req) {
        fileService.ackDeleteTask(taskId, req.success(), req.errorMessage());
        return Result.success();
    }

    private StorageDtos.OriginFileView toOriginView(StorageFileEntity file) {
        String telegramFileId = null;
        try {
            JsonNode locator = objectMapper.readTree(file.getProviderLocatorJson());
            telegramFileId = locator.path("fileId").asText(null);
        } catch (Exception e) {
            log.warn("[Storage] locator 解析失败: publicId={}", file.getPublicId());
        }
        if (telegramFileId == null || telegramFileId.isBlank()) {
            throw new BusinessException("STORAGE016");
        }
        return new StorageDtos.OriginFileView(file.getPublicId(), file.getContentVersion(), file.getStatus(),
                file.getVisibility(), file.getContentType(), file.getSizeBytes(),
                file.getOriginalName(), telegramFileId);
    }
}
