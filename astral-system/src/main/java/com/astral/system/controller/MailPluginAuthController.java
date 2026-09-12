package com.astral.system.controller;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailPluginAuth;
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

    /** 查看权限（sys_permission: system:mail:view） */
    private static final String PERM_VIEW = "system:mail:view";
    /** 维护权限（sys_permission: system:mail:plugin-auth:edit） */
    private static final String PERM_EDIT = "system:mail:plugin-auth:edit";

    private final SysMailPluginAuthService pluginAuthService;
    private final PermissionChecker permissionChecker;

    @Operation(summary = "列表")
    @GetMapping("/list")
    public Result<List<SysMailPluginAuth>> list() {
        permissionChecker.require(PERM_VIEW);
        return Result.success(pluginAuthService.list());
    }

    @Operation(summary = "新增授权")
    @PostMapping
    public Result<Void> create(@RequestBody SysMailPluginAuth entity) {
        permissionChecker.require(PERM_EDIT);
        pluginAuthService.save(entity);
        return Result.success();
    }

    @Operation(summary = "更新授权")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMailPluginAuth entity) {
        permissionChecker.require(PERM_EDIT);
        entity.setId(id);
        pluginAuthService.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除授权")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionChecker.require(PERM_EDIT);
        pluginAuthService.removeById(id);
        return Result.success();
    }
}
