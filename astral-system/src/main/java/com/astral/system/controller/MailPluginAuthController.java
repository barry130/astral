package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailPluginAuth;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailPluginAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
        pluginAuthService.save(entity);
        mailService.evictPluginAuthCache();
        return Result.success();
    }

    @Operation(summary = "更新授权")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMailPluginAuth entity) {
        entity.setId(id);
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
}
