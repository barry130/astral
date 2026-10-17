package com.astral.storage.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.*;
import com.astral.storage.service.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.astral.storage.mapper.StorageFileMapper;
import com.astral.storage.mapper.StorageFolderMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

/**
 * storage 插件管理端 API（/api/v1/admin/plugin/storage/**）
 * <p>登录态由宿主 AuthInterceptor 校验；本控制器只处理存储管理业务。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/plugin/storage")
@RequiresPermission(value = "admin:storage:view", name = "存储查看", description = "存储概览/配置/文件夹/文件/任务与审计查看")
@RequiredArgsConstructor
public class AdminStorageController {

    private final StorageConfigService configService;
    private final StorageFolderService folderService;
    private final StorageFileService fileService;
    private final StorageAuditService auditService;
    private final StorageFileMapper fileMapper;
    private final StorageFolderMapper folderMapper;
    private final FileManagerService fileManagerService;
    private final StorageBatchService batchService;
    private final com.astral.storage.security.UploadTicketService ticketService;

    private static String userId(jakarta.servlet.http.HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId == null ? null : userId.toString();
    }

    // ==================== 概览 ====================

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(jakarta.servlet.http.HttpServletRequest request) {
        Map<String, Object> data = new HashMap<>();
        data.put("fileCount", fileMapper.selectCount(null));
        data.put("folderCount", folderMapper.selectCount(null));
        data.put("configCount", configService.listAll().size());
        data.put("operator", userId(request));
        return Result.success(data);
    }

    // ==================== 存储配置 ====================

    @GetMapping("/configs")
    public Result<List<StorageConfigEntity>> configs() {
        return Result.success(configService.listAll());
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PostMapping("/configs")
    public Result<StorageConfigEntity> createConfig(@RequestBody StorageDtos.ConfigCreateReq req,
                                                    jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(configService.create(req, userId(request)));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PutMapping("/configs/{id}")
    public Result<StorageConfigEntity> updateConfig(@PathVariable Long id,
                                                    @RequestBody StorageDtos.ConfigUpdateReq req,
                                                    jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(configService.update(id, req, userId(request)));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @DeleteMapping("/configs/{id}")
    public Result<Void> deleteConfig(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        configService.delete(id, userId(request));
        return Result.success();
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PostMapping("/configs/{id}/test")
    public Result<StorageDtos.ConfigTestResp> testConfig(@PathVariable Long id,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(configService.test(id, userId(request)));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PostMapping("/configs/{id}/default")
    public Result<Void> setDefaultConfig(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        configService.setDefault(id, userId(request));
        return Result.success();
    }

    // ==================== 文件夹与授权 ====================

    @GetMapping("/folders")
    public Result<List<StorageFolderEntity>> folders() {
        return Result.success(folderService.listAll());
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PostMapping("/folders")
    public Result<StorageFolderEntity> createFolder(@RequestBody StorageDtos.FolderCreateReq req,
                                                    jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(folderService.create(req, userId(request)));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PutMapping("/folders/{id}")
    public Result<StorageFolderEntity> updateFolder(@PathVariable Long id,
                                                    @RequestBody StorageDtos.FolderUpdateReq req,
                                                    jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(folderService.update(id, req, userId(request)));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @DeleteMapping("/folders/{id}")
    public Result<Void> deleteFolder(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        folderService.delete(id, userId(request));
        return Result.success();
    }

    @GetMapping("/folders/{id}/permissions")
    public Result<List<StorageFolderPermissionEntity>> folderPermissions(@PathVariable Long id) {
        return Result.success(folderService.listPermissions(id));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PutMapping("/folders/{id}/permissions")
    public Result<Void> saveFolderPermissions(@PathVariable Long id,
                                              @RequestBody StorageDtos.FolderPermSaveReq req,
                                              jakarta.servlet.http.HttpServletRequest request) {
        folderService.savePermissions(id, req.rows(), userId(request));
        return Result.success();
    }

    // ==================== 文件管理 ====================

    @GetMapping("/files")
    public Result<Page<StorageFileEntity>> files(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) Long folderId,
                                                 @RequestParam(required = false) String keyword) {
        return Result.success(fileService.pageForAdmin(current, size, folderId, keyword));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PutMapping("/files/{publicId}/visibility")
    public Result<StorageFileEntity> changeVisibility(@PathVariable String publicId,
                                                      @RequestParam String value,
                                                      jakarta.servlet.http.HttpServletRequest request) {
        StorageFileEntity file = fileService.changeVisibility(publicId, userId(request), value, true);
        file.setProviderLocatorJson(null);
        return Result.success(file);
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @DeleteMapping("/files/{publicId}")
    public Result<Void> deleteFile(@PathVariable String publicId, jakarta.servlet.http.HttpServletRequest request) {
        fileService.requestDelete(publicId, userId(request), true);
        return Result.success();
    }

    // ==================== 任务与审计 ====================

    @GetMapping("/tasks")
    public Result<Page<StorageTaskEntity>> tasks(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "20") long size) {
        // 复用文件分页逻辑之外的任务查询：直接通过审计服务所在包的 Mapper 由服务层暴露
        return Result.success(fileService.pageTasks(current, size));
    }

    @GetMapping("/audit")
    public Result<Page<StorageAuditEntity>> audit(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "20") long size) {
        return Result.success(auditService.page(current, size));
    }

    @GetMapping("/manager/files")
    public Result<Page<StorageFileEntity>> managerFiles(@RequestParam(defaultValue = "1") long current,
                                                       @RequestParam(defaultValue = "24") long size,
                                                       StorageDtos.FileQuery query,
                                                       jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(fileManagerService.page(userId(request), true, current, size, query));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PutMapping("/files/{publicId}")
    public Result<StorageFileEntity> updateFile(@PathVariable String publicId, @RequestBody StorageDtos.FileUpdateReq req,
                                               jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(fileManagerService.update(publicId, req, userId(request), true));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PostMapping("/files/batch")
    public Result<StorageDtos.BatchResult> batch(@RequestBody StorageDtos.FileBatchReq req,
                                                jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(batchService.execute(req, userId(request), true));
    }

    @PostMapping("/files/{publicId}/download-url")
    public Result<StorageDtos.DownloadUrlView> downloadUrl(@PathVariable String publicId,
                                                          jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(fileService.issueDownloadUrl(publicId, userId(request), true));
    }

    @PostMapping("/files/{publicId}/permanent-url")
    public Result<StorageDtos.PermanentUrlView> permanentUrl(@PathVariable String publicId,
                                                          jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(fileService.issuePermanentUrl(publicId, userId(request), true));
    }

    @RequiresPermission(value = "admin:storage:edit", name = "存储编辑", description = "存储配置/文件夹策略/文件操作")
    @PostMapping("/upload-ticket")
    public Result<StorageDtos.TicketView> uploadTicket(@RequestBody StorageDtos.UploadTicketReq req,
                                                      jakarta.servlet.http.HttpServletRequest request) {
        if (req.folderId() == null) throw new com.astral.common.exception.BusinessException("COMMON002", "缺少 folderId");
        StorageFolderEntity folder = folderService.getById(req.folderId());
        StorageConfigEntity config = configService.getById(folder.getStorageConfigId());
        if (!StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) throw new com.astral.common.exception.BusinessException("STORAGE002");
        var issued = ticketService.issue(config, folder, userId(request), req.fileName(), req.contentType(), req.sizeBytes(), true);
        var form = issued.formFields() == null ? null : new StorageDtos.TicketFormView(issued.formFields().policy(), issued.formFields().authorization());
        return Result.success(new StorageDtos.TicketView(issued.uploadUrl(), issued.method(), issued.formField(),
                issued.uploadId(), issued.expiresAtEpochSeconds(), form));
    }
}
