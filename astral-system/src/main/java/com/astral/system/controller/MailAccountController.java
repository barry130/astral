package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailAccount;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailAccountService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

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

    /**
     * 抹去凭据字段后再出参。
     * <p>{@code password} 是邮箱 SMTP 授权码，等价于该邮箱的发信凭据——持有「查看」权限
     * 不等于应当读到明文。列表与详情一律不回传；编辑保存走 {@link #update} 的
     * lambdaUpdate 白名单按需更新：前端留空（不传 {@code password}）即不修改原授权码，
     * 传空串则被显式拒绝，不会把已存授权码冲掉。</p>
     */
    private static SysMailAccount withoutCredential(SysMailAccount entity) {
        if (entity != null) {
            entity.setPassword(null);
        }
        return entity;
    }

    @Operation(summary = "分页查询")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/page")
    public Result<Page<SysMailAccount>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysMailAccount> page = accountService.page(new Page<>(pageNum, pageSize));
        page.getRecords().forEach(MailAccountController::withoutCredential);
        return Result.success(page);
    }

    @Operation(summary = "详情")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/{id}")
    public Result<SysMailAccount> getById(@PathVariable Long id) {
        return Result.success(withoutCredential(accountService.getById(id)));
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
        if (accountService.getById(id) == null) {
            throw new BusinessException("MAIL001");
        }
        // 授权码三态：null = 不修改（前端编辑留空时不传该字段）；空串 = 非法（全实体更新语义下会把
        // 已存授权码清空，这里显式拒绝，提示调用方「不改就别传」）；非空 = 覆盖。
        if (entity.getPassword() != null && entity.getPassword().isBlank()) {
            throw new BusinessException("MAIL007");
        }
        // 白名单 + 按需更新。不能用 updateById(entity)：实测 MP 3.5.17 即便字段被显式置 null
        // 也会写进 SET 子句（见 AGENTS §5 / RoleController.update 同款说明），直调 API 即可把
        // 任意未传字段写坏；这里逐字段判 null，只写真正传了的。
        var wrapper = accountService.lambdaUpdate().eq(SysMailAccount::getId, id);
        if (entity.getAccountName() != null) {
            wrapper.set(SysMailAccount::getAccountName, entity.getAccountName());
        }
        if (entity.getSmtpHost() != null) {
            wrapper.set(SysMailAccount::getSmtpHost, entity.getSmtpHost());
        }
        if (entity.getSmtpPort() != null) {
            wrapper.set(SysMailAccount::getSmtpPort, entity.getSmtpPort());
        }
        if (entity.getUsername() != null) {
            wrapper.set(SysMailAccount::getUsername, entity.getUsername());
        }
        if (entity.getPassword() != null) {
            wrapper.set(SysMailAccount::getPassword, entity.getPassword());
        }
        if (entity.getFromAddr() != null) {
            wrapper.set(SysMailAccount::getFromAddr, entity.getFromAddr());
        }
        if (entity.getFromName() != null) {
            wrapper.set(SysMailAccount::getFromName, entity.getFromName());
        }
        if (entity.getSslEnable() != null) {
            wrapper.set(SysMailAccount::getSslEnable, entity.getSslEnable());
        }
        if (entity.getStarttlsEnable() != null) {
            wrapper.set(SysMailAccount::getStarttlsEnable, entity.getStarttlsEnable());
        }
        if (entity.getWeight() != null) {
            wrapper.set(SysMailAccount::getWeight, entity.getWeight());
        }
        if (entity.getRemark() != null) {
            wrapper.set(SysMailAccount::getRemark, entity.getRemark());
        }
        wrapper.set(SysMailAccount::getUpdateTime, LocalDateTime.now());
        wrapper.update();
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
        // 同 update：单字段局部更新也走 lambdaUpdate 白名单，不构造半空实体走 updateById
        accountService.lambdaUpdate()
                .eq(SysMailAccount::getId, id)
                .set(SysMailAccount::getEnabled, enabled)
                .set(SysMailAccount::getUpdateTime, LocalDateTime.now())
                .update();
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
