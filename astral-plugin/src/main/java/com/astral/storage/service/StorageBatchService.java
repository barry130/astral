package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.dto.StorageDtos;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 批量中的各文件分别调用代理服务，以独立事务提交并报告部分失败。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageBatchService {
    private final StorageFileService fileService;
    private final FileManagerService managerService;

    public StorageDtos.BatchResult execute(StorageDtos.FileBatchReq req, String userId, boolean admin) {
        if (req.publicIds() == null || req.publicIds().isEmpty() || req.publicIds().size() > 100
                || req.publicIds().stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 128)) {
            throw new BusinessException("COMMON002", "请选择 1–100 个文件");
        }
        if (req.action() == null || !java.util.Set.of("delete", "move", "visibility").contains(req.action())
                || ("move".equals(req.action()) && req.folderId() == null)
                || ("visibility".equals(req.action()) && !java.util.Set.of("PUBLIC", "PRIVATE").contains(req.visibility() == null ? "" : req.visibility()))) {
            throw new BusinessException("COMMON002", "批量操作参数无效");
        }
        var succeeded = new java.util.ArrayList<String>();
        var failed = new java.util.ArrayList<StorageDtos.BatchFailure>();
        for (String id : new java.util.LinkedHashSet<>(req.publicIds())) {
            try {
                switch (req.action()) {
                    case "delete" -> fileService.requestDelete(id, userId, admin);
                    case "move" -> managerService.update(id, new StorageDtos.FileUpdateReq(null, req.folderId()), userId, admin);
                    case "visibility" -> fileService.changeVisibility(id, userId, req.visibility(), admin);
                    default -> throw new IllegalStateException("Unsupported batch action");
                }
                succeeded.add(id);
            } catch (BusinessException e) {
                failed.add(new StorageDtos.BatchFailure(id, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Batch {} failed for file {}", req.action(), id, e);
                failed.add(new StorageDtos.BatchFailure(id, "操作失败，请稍后重试"));
            }
        }
        return new StorageDtos.BatchResult(succeeded, failed);
    }
}
