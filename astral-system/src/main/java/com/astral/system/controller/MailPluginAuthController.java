package com.astral.system.controller;

import java.util.List;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailPluginAuth;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailPluginAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "发信授权")
@RestController
@RequestMapping("/api/v1/admin/system/mail/plugin-auth")
@RequiredArgsConstructor
public class MailPluginAuthController {

    /** 查看权限（sys_permission: admin:system:mail:view） */
    private static final String PERM_VIEW = "admin:system:mail:view";
    /** 维护权限（sys_permission: admin:system:mail:plugin-auth:edit） */
    private static final String PERM_EDIT = "admin:system:mail:plugin-auth:edit";

    private final SysMailPluginAuthService pluginAuthService;

    /** 发送路径的授权读取走 60s 进程内缓存，写完必须失效 */
    private final MailService mailService;

    @Operation(summary = "列表")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/list")
    public Result<List<SysMailPluginAuth>> list() {
        return Result.success(pluginAuthService.list());
    }

    @Operation(summary = "新增授权")
    @RequiresPermission(PERM_EDIT)
    @PostMapping
    public Result<Void> create(@RequestBody SysMailPluginAuth entity) {
        validateAuth(entity, true, null);
        pluginAuthService.save(entity);
        mailService.evictPluginAuthCache();
        return Result.success();
    }

    @Operation(summary = "更新授权")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMailPluginAuth entity) {
        entity.setId(id);
        validateAuth(entity, false, id);
        pluginAuthService.updateById(entity);
        mailService.evictPluginAuthCache();
        return Result.success();
    }

    @Operation(summary = "删除授权")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        pluginAuthService.removeById(id);
        mailService.evictPluginAuthCache();
        return Result.success();
    }

    /**
     * 授权写路径校验：allowed_scenes 里的每一项必须是 {@link NotifyEventRegistry} 登记的事件——
     * 授权的价值就在「插件只能在获批事件内发信」，事件不校验白名单就是摆设。
     * 留空（NULL/空白）= <b>一律拒绝发送</b>（fail-closed，发送侧 MAIL018），缺省不猜。
     */
    private void validateAuth(SysMailPluginAuth entity, boolean isCreate, Long id) {
        boolean pluginIdPresent = entity.getPluginId() != null && !entity.getPluginId().isBlank();
        if (isCreate && !pluginIdPresent) {
            throw new BusinessException("MAIL017");
        }
        if (pluginIdPresent) {
            Long dup = pluginAuthService.lambdaQuery()
                    .eq(SysMailPluginAuth::getPluginId, entity.getPluginId())
                    .ne(id != null, SysMailPluginAuth::getId, id)
                    .count();
            if (dup != null && dup > 0) {
                throw new BusinessException("MAIL011", entity.getPluginId());
            }
        }
        if (entity.getAllowedScenes() != null && !entity.getAllowedScenes().isBlank()) {
            for (String scene : NotifyEventRegistry.parseList(entity.getAllowedScenes())) {
                if (!NotifyEventRegistry.exists(scene)) {
                    throw new BusinessException("MAIL012", scene);
                }
            }
        }
    }
}
