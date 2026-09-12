package com.astral.system.controller;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailAccount;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailAccountService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "邮箱账户")
@RestController
@RequestMapping("/api/v1/admin/system/mail/account")
@RequiredArgsConstructor
public class MailAccountController {

    /** 查看权限（sys_permission: system:mail:view） */
    private static final String PERM_VIEW = "system:mail:view";
    /** 维护权限（sys_permission: system:mail:account:edit） */
    private static final String PERM_EDIT = "system:mail:account:edit";

    private final SysMailAccountService accountService;
    private final MailService mailService;
    private final PermissionChecker permissionChecker;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<SysMailAccount>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize) {
        permissionChecker.require(PERM_VIEW);
        return Result.success(accountService.page(new Page<>(pageNum, pageSize)));
    }

    @Operation(summary = "详情")
    @GetMapping("/{id}")
    public Result<SysMailAccount> getById(@PathVariable Long id) {
        permissionChecker.require(PERM_VIEW);
        return Result.success(accountService.getById(id));
    }

    @Operation(summary = "新增")
    @PostMapping
    public Result<Void> create(@RequestBody SysMailAccount entity) {
        permissionChecker.require(PERM_EDIT);
        accountService.save(entity);
        return Result.success();
    }

    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMailAccount entity) {
        permissionChecker.require(PERM_EDIT);
        entity.setId(id);
        accountService.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        permissionChecker.require(PERM_EDIT);
        accountService.removeById(id);
        return Result.success();
    }

    @Operation(summary = "启用/停用")
    @PostMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Integer enabled) {
        permissionChecker.require(PERM_EDIT);
        SysMailAccount acc = new SysMailAccount();
        acc.setId(id);
        acc.setEnabled(enabled);
        accountService.updateById(acc);
        return Result.success();
    }

    @Operation(summary = "测试发送")
    @PostMapping("/test")
    public Result<Void> test(@RequestParam Long accountId, @RequestParam String toEmail) {
        permissionChecker.require(PERM_EDIT);
        mailService.testSend(accountId, toEmail);
        return Result.success();
    }
}
