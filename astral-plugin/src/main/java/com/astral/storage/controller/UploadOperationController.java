package com.astral.storage.controller;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.storage.dto.UploadOperationDtos;
import com.astral.storage.service.UploadOperationService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 两段式上传 API（§7.1–§7.4）。
 *
 * <ul>
 *   <li>POST /upload-operations：创建意图（幂等键必填，只落库不占配额）；</li>
 *   <li>GET  /upload-operations/{uploadId}：查询状态，只有 COMMITTED 才是成功；</li>
 *   <li>PUT  /upload-operations/{uploadId}/content：发送正文，先做全部校验再读 body。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/all/storage/upload-operations")
@RequiredArgsConstructor
public class UploadOperationController {

    private final UploadOperationService operationService;
    private final PermissionChecker permissionChecker;

    private static String userId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId == null ? null : userId.toString();
    }

    private boolean adminExempt() {
        return permissionChecker.hasPermission(PermissionChecker.SUPER_PERMISSION);
    }

    @PostMapping
    @RequiresPermission(value = "all:storage:upload:create", name = "发起上传", description = "创建两段式上传意图")
    public Result<UploadOperationDtos.UploadCreateResp> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody UploadOperationDtos.UploadCreateReq req,
            HttpServletRequest request) {
        // 管理员豁免语义拆分后保持等价：目录 ACL 与归属校验同时豁免
        return Result.success(operationService.create(req, userId(request), idempotencyKey, adminExempt()));
    }

    @GetMapping("/{uploadId}")
    @RequiresPermission(value = "all:storage:upload:view", name = "查看上传", description = "查询两段式上传操作状态")
    public Result<UploadOperationDtos.UploadOperationView> view(
            @PathVariable String uploadId, HttpServletRequest request) {
        return Result.success(operationService.get(uploadId, userId(request), adminExempt()));
    }

    @PutMapping("/{uploadId}/content")
    @RequiresPermission(value = "all:storage:upload:create", name = "发送上传正文", description = "向上游发送两段式上传正文")
    public Result<UploadOperationDtos.UploadContentResp> content(
            @PathVariable String uploadId,
            @RequestHeader(value = "Content-Length", required = false) Long declaredLength,
            @RequestHeader(value = "Content-Type", required = false) String contentType,
            HttpServletRequest request)
            throws IOException {
        long contentLength = declaredLength == null ? request.getContentLengthLong() : declaredLength;
        UploadOperationService.PreparedContent prepared = operationService.prepareContent(
                uploadId, contentLength, contentType, userId(request), adminExempt(), adminExempt());
        try (prepared) {
            byte[] body = readBounded(request, prepared.maxBytes());
            return Result.success(operationService.sendContent(prepared, body));
        }
    }

    /**
     * 读取正文并做硬上限保护：只多读 1 字节判断超限，避免无界缓冲（§11.2）。
     */
    private byte[] readBounded(HttpServletRequest request, long maxBytes) throws IOException {
        try (InputStream in = request.getInputStream()) {
            byte[] body = in.readNBytes((int) Math.min(maxBytes + 1, Integer.MAX_VALUE));
            if (body.length > maxBytes) {
                throw new BusinessException("STORAGE045", "正文超出允许上限");
            }
            return body;
        }
    }
}
