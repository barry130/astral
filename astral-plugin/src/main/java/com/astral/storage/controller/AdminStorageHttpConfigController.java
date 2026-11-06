package com.astral.storage.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.storage.dto.HttpChannelDtos;
import com.astral.storage.service.HttpStorageConfigService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CUSTOM_HTTP 渠道管理 API（/api/v1/admin/plugin/storage/http-configs/**）。
 *
 * <p>与旧 {@link AdminStorageController} 的 {@code /configs} 完全分离：旧路由的写/test/default
 * 在服务层明确拒绝 CUSTOM_HTTP，新渠道只能走本控制器。权限按设计 §4.4：
 * 读取/离线校验 {@code admin:storage:channel:view}、写入 {@code admin:storage:channel:edit}、
 * 真实测试 {@code admin:storage:channel:test}。</p>
 *
 * <p>敏感路由策略：本控制器<b>不加</b> {@code @OperateLog}，避免自动记录画像/凭据/请求体；
 * 审计事件由服务层用安全摘要落 {@code sys_storage_audit}（设计 §15）。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/plugin/storage/http-configs")
@RequiredArgsConstructor
@RequiresPermission(value = "admin:storage:channel:view", name = "存储渠道查看", description = "HTTP 渠道摘要/安全详情/批准目标/离线校验")
public class AdminStorageHttpConfigController {

    private final HttpStorageConfigService channelService;

    private static String userId(jakarta.servlet.http.HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        return userId == null ? null : userId.toString();
    }

    /** 渠道摘要列表 */
    @GetMapping
    public Result<List<HttpChannelDtos.ChannelSummary>> list() {
        return Result.success(channelService.listSummaries());
    }

    /** 部署侧批准目标（只读，管理接口不能扩大清单） */
    @GetMapping("/approved-targets")
    public Result<List<HttpChannelDtos.ApprovedTargetView>> approvedTargets() {
        return Result.success(channelService.approvedTargets());
    }

    /** 渠道安全详情：profile 可见、凭据只列是否已配置 */
    @GetMapping("/{id}")
    public Result<HttpChannelDtos.ChannelView> detail(@PathVariable Long id) {
        return Result.success(channelService.get(id));
    }

    /** 新建渠道（默认 DISABLED；启用必须凭据齐全） */
    @RequiresPermission(
            value = "admin:storage:channel:edit",
            name = "存储渠道编辑",
            description = "HTTP 渠道创建/参数与凭据修改/状态修改/删除")
    @PostMapping
    public Result<HttpChannelDtos.ChannelView> create(
            @RequestBody HttpChannelDtos.ChannelCreateReq req, jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(channelService.create(req, userId(request)));
    }

    /** 更新渠道：必须带 expectedVersion，冲突 STORAGE038 */
    @RequiresPermission(
            value = "admin:storage:channel:edit",
            name = "存储渠道编辑",
            description = "HTTP 渠道创建/参数与凭据修改/状态修改/删除")
    @PutMapping("/{id}")
    public Result<HttpChannelDtos.ChannelView> update(
            @PathVariable Long id,
            @RequestBody HttpChannelDtos.ChannelUpdateReq req,
            jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(channelService.update(id, req, userId(request)));
    }

    /** 删除渠道：仍被文件/文件夹/存储组引用时拒绝 */
    @RequiresPermission(
            value = "admin:storage:channel:edit",
            name = "存储渠道编辑",
            description = "HTTP 渠道创建/参数与凭据修改/状态修改/删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        channelService.delete(id, userId(request));
        return Result.success();
    }

    /** 离线校验：不联网、不读写数据库 */
    @PostMapping("/validate")
    public Result<HttpChannelDtos.ChannelValidationView> validate(@RequestBody HttpChannelDtos.ChannelValidateReq req) {
        return Result.success(channelService.validateOffline(req));
    }

    /** 真实渠道上传测试：有副作用，单独权限；不伪造成功 */
    @RequiresPermission(value = "admin:storage:channel:test", name = "存储渠道测试", description = "HTTP 渠道真实上传测试（合成内容、有副作用）")
    @PostMapping("/{id}/test")
    public Result<HttpChannelDtos.ChannelTestView> test(
            @PathVariable Long id, jakarta.servlet.http.HttpServletRequest request) {
        return Result.success(channelService.test(id, userId(request)));
    }
}
