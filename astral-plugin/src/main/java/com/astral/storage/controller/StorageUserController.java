package com.astral.storage.controller;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.result.Result;
import com.astral.storage.dto.StorageDtos;
import com.astral.storage.entity.StorageFileEntity;
import com.astral.storage.service.StorageFileService;
import com.astral.storage.security.UploadTicketService;
import com.astral.storage.entity.StorageConfigEntity;
import com.astral.storage.entity.StorageFolderEntity;
import com.astral.storage.service.StorageConfigService;
import com.astral.storage.service.StorageFolderService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * storage 插件用户/业务 API（/api/v1/all/storage/**）
 * <p>登录态由宿主 AuthInterceptor 校验；文件夹级权限在本层校验（STORAGE013）。
 * Worker 专属路径（worker/origin 子树）由 HMAC 拦截器处理，见 StorageWorkerController。</p>
 */
@RestController
@RequestMapping("/api/v1/all/storage")
@RequiredArgsConstructor
public class StorageUserController {

    private final StorageConfigService configService;
    private final StorageFolderService folderService;
    private final StorageFileService fileService;
    private final UploadTicketService ticketService;
    private final PermissionChecker permissionChecker;

    private static String userId(jakarta.servlet.http.HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId == null ? null : userId.toString();
    }

    /** 用户侧「我的文件夹」列表：本人所有 + 授权给我的（含当前用户权限集合），供图床等用户页面选择目标文件夹 */
    @GetMapping("/folders")
    public Result<List<StorageDtos.FolderMineView>> myFolders(jakarta.servlet.http.HttpServletRequest request) {
        // 管理员可见全部文件夹（与后台管理页一致）；普通用户仅见本人所有与已授权文件夹
        if (permissionChecker.hasPermission(PermissionChecker.SUPER_PERMISSION)) {
            return Result.success(folderService.listAllAsMine());
        }
        return Result.success(folderService.listForUser(userId(request)));
    }

    /**
     * 换取短时上传凭证：TELEGRAM 返回 Worker 直传地址（POST multipart）；
     * S3 系/COS/OSS 返回预签名 PUT 地址（method=PUT，无 formField）；
     * UPYUN 返回表单 API 地址 + form.policy/form.authorization（POST multipart）。
     * Astral 不接收文件正文。
     */
    @PostMapping("/upload-ticket")
    public Result<StorageDtos.TicketView> uploadTicket(@RequestBody StorageDtos.UploadTicketReq req,
                                                       jakarta.servlet.http.HttpServletRequest request) {
        String userId = userId(request);
        if (req.folderId() == null) {
            throw new com.astral.common.exception.BusinessException("COMMON002", "缺少 folderId");
        }
        StorageFolderEntity folder = folderService.getById(req.folderId());
        folderService.requirePermission(userId, folder.getId(), "UPLOAD");
        // 配置取文件夹绑定的存储配置；被禁用时明确拒绝
        StorageConfigEntity config = configService.getById(folder.getStorageConfigId());
        if (!StorageConfigEntity.STATUS_ENABLED.equals(config.getStatus())) {
            throw new com.astral.common.exception.BusinessException("STORAGE002");
        }
        UploadTicketService.IssuedTicket issued = ticketService.issue(
                config, folder, userId, req.fileName(), req.contentType(), req.sizeBytes());
        StorageDtos.TicketFormView form = issued.formFields() == null ? null
                : new StorageDtos.TicketFormView(
                        issued.formFields().policy(), issued.formFields().authorization());
        return Result.success(new StorageDtos.TicketView(
                issued.uploadUrl(), issued.method(), issued.formField(),
                issued.uploadId(), issued.expiresAtEpochSeconds(), form));
    }

    /**
     * 对象存储直传完成回执：浏览器凭 uploadId 通知登记；服务端 HEAD 对象确认存在后落库。
     * TELEGRAM 上传由 Worker 回调登记，调用本接口返回 STORAGE019。
     */
    @PostMapping("/files/register")
    public Result<StorageFileEntity> registerUpload(@RequestBody StorageDtos.RegisterUploadReq req,
                                                    jakarta.servlet.http.HttpServletRequest request) {
        StorageFileEntity file = fileService.registerFromBrowser(req.uploadId(), userId(request));
        file.setProviderLocatorJson(null);
        return Result.success(file);
    }

    /** 永久公开链接（仅 PUBLIC 文件；TELEGRAM 走 Worker /p/，R2/S3 走桶公开域名） */
    @PostMapping("/files/{publicId}/permanent-url")
    public Result<StorageDtos.PermanentUrlView> permanentUrl(@PathVariable String publicId,
                                                             jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(fileService.issuePermanentUrl(publicId, userId(request)));
    }

    /** 签发短时下载 URL（私有/公开统一走签名） */
    @PostMapping("/files/{publicId}/download-url")
    public Result<StorageDtos.DownloadUrlView> downloadUrl(@PathVariable String publicId,
                                                           jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(fileService.issueDownloadUrl(publicId, userId(request)));
    }

    @GetMapping("/files/{publicId}")
    public Result<StorageFileEntity> file(@PathVariable String publicId,
                                          jakarta.servlet.http.HttpServletRequest request) {
        StorageFileEntity file = fileService.getByPublicId(publicId);
        String userId = userId(request);
        boolean uploader = userId != null && userId.equals(file.getUploaderId());
        if (!uploader) {
            folderService.requirePermission(userId, file.getFolderId(), "READ");
        }
        file.setProviderLocatorJson(null);
        return Result.success(file);
    }

    @GetMapping("/folders/{folderId}/files")
    public Result<Page<StorageFileEntity>> folderFiles(@PathVariable Long folderId,
                                                       @RequestParam(defaultValue = "1") long current,
                                                       @RequestParam(defaultValue = "20") long size,
                                                       jakarta.servlet.http.HttpServletRequest request) {
        Page<StorageFileEntity> page = fileService.pageFolderFiles(folderId, userId(request), current, size);
        page.getRecords().forEach(f -> f.setProviderLocatorJson(null));
        return Result.success(page);
    }

    @PutMapping("/files/{publicId}/visibility")
    public Result<StorageFileEntity> changeVisibility(@PathVariable String publicId,
                                                      @RequestParam String value,
                                                      jakarta.servlet.http.HttpServletRequest request) {
        StorageFileEntity file = fileService.changeVisibility(publicId, userId(request), value);
        file.setProviderLocatorJson(null);
        return Result.success(file);
    }

    @DeleteMapping("/files/{publicId}")
    public Result<Void> deleteFile(@PathVariable String publicId, jakarta.servlet.http.HttpServletRequest request) {
        fileService.requestDelete(publicId, userId(request));
        return Result.success();
    }
}
