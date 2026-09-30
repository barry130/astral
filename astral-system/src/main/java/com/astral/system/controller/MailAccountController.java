package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
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

    /** 查看权限（sys_permission: admin:system:mail:view） */
    private static final String PERM_VIEW = "admin:system:mail:view";
    /** 维护权限（sys_permission: admin:system:mail:account:edit） */
    private static final String PERM_EDIT = "admin:system:mail:account:edit";

    private final SysMailAccountService accountService;
    private final MailService mailService;

    @Operation(summary = "分页查询")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/page")
    public Result<Page<SysMailAccount>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(accountService.page(new Page<>(pageNum, pageSize)));
    }

    @Operation(summary = "详情")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/{id}")
    public Result<SysMailAccount> getById(@PathVariable Long id) {
        return Result.success(accountService.getById(id));
    }

    @Operation(summary = "新增")
    @RequiresPermission(PERM_EDIT)
    @PostMapping
    public Result<Void> create(@RequestBody SysMailAccount entity) {
        accountService.save(entity);
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMailAccount entity) {
        entity.setId(id);
        accountService.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        accountService.removeById(id);
        return Result.success();
    }

    @Operation(summary = "启用/停用")
    @RequiresPermission(PERM_EDIT)
    @PostMapping("/{id}/toggle")
    public Result<Void> toggle(@PathVariable Long id, @RequestParam Integer enabled) {
        SysMailAccount acc = new SysMailAccount();
        acc.setId(id);
        acc.setEnabled(enabled);
        accountService.updateById(acc);
        return Result.success();
    }

    @Operation(summary = "测试发送")
    @RequiresPermission(PERM_EDIT)
    @PostMapping("/test")
    public Result<Void> test(@RequestParam Long accountId, @RequestParam String toEmail) {
        mailService.testSend(accountId, toEmail);
        return Result.success();
    }
}
