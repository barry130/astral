package com.astral.system.controller;

import java.time.LocalDateTime;
import java.util.List;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.system.notify.SmsService;
import com.astral.system.notify.sms.SmsProvider;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "短信供应商")
@RestController
@RequestMapping("/api/v1/admin/system/sms/provider")
@RequiredArgsConstructor
public class SmsProviderController {

    /** 查看权限（自动注册：短信通知查看） */
    private static final String PERM_VIEW = "admin:system:notify:sms:view";
    /** 维护权限（自动注册：短信通知维护） */
    private static final String PERM_EDIT = "admin:system:notify:sms:edit";

    private final com.astral.system.notify.SysSmsProviderService providerService;
    private final SmsService smsService;

    /** 进程内 SPI 实现清单，用于校验 providerType 是否有落地实现 */
    private final List<SmsProvider> providerImpls;

    /**
     * 抹去凭据字段后再出参（与 {@link MailAccountController} 同款约束）。
     * <p>{@code accessSecret} 等价于短信账户的调用凭据，持有「查看」权限不等于应当读到明文；
     * 编辑保存走 {@link #update} 白名单按需更新：前端留空（不传）即不修改原密钥。</p>
     */
    private static SysSmsProvider withoutCredential(SysSmsProvider entity) {
        if (entity != null) {
            entity.setAccessSecret(null);
        }
        return entity;
    }

    @Operation(summary = "分页查询")
    @RequiresPermission(value = PERM_VIEW, name = "短信通知查看", description = "查看短信供应商、模板与发送日志")
    @GetMapping("/page")
    public Result<Page<SysSmsProvider>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<SysSmsProvider> page = providerService.page(new Page<>(pageNum, pageSize));
        page.getRecords().forEach(SmsProviderController::withoutCredential);
        return Result.success(page);
    }

    @Operation(summary = "详情")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/{id}")
    public Result<SysSmsProvider> getById(@PathVariable Long id) {
        return Result.success(withoutCredential(providerService.getById(id)));
    }

    @Operation(summary = "可用的供应商类型（SPI 已注册）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/types")
    public Result<List<String>> types() {
        return Result.success(providerImpls.stream().map(SmsProvider::type).sorted().toList());
    }

    @Operation(summary = "新增")
    @RequiresPermission(value = PERM_EDIT, name = "短信通知维护", description = "维护短信供应商与模板配置")
    @PostMapping
    public Result<Void> create(@RequestBody SysSmsProvider entity) {
        validate(entity, null);
        providerService.save(entity);
        smsService.evictProviderCache();
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysSmsProvider entity) {
        if (providerService.getById(id) == null) {
            throw new BusinessException("SMS001");
        }
        validate(entity, id);
        // accessSecret 三态：null = 不修改；空串 = 显式拒绝（会冲掉已存密钥）；非空 = 覆盖。
        // 白名单按需更新：MP 3.5.17 updateById 即便字段显式置 null 也会写进 SET 子句，
        // 直调 API 会把未传字段写坏（MailAccountController 同款约束）。
        if (entity.getAccessSecret() != null && entity.getAccessSecret().isBlank()) {
            throw new BusinessException("MAIL007");
        }
        var wrapper = providerService.lambdaUpdate().eq(SysSmsProvider::getId, id);
        if (entity.getProviderName() != null) {
            wrapper.set(SysSmsProvider::getProviderName, entity.getProviderName());
        }
        if (entity.getProviderType() != null) {
            wrapper.set(SysSmsProvider::getProviderType, entity.getProviderType());
        }
        if (entity.getAccessKey() != null) {
            wrapper.set(SysSmsProvider::getAccessKey, entity.getAccessKey());
        }
        if (entity.getAccessSecret() != null) {
            wrapper.set(SysSmsProvider::getAccessSecret, entity.getAccessSecret());
        }
        if (entity.getSignName() != null) {
            wrapper.set(SysSmsProvider::getSignName, entity.getSignName());
        }
        if (entity.getRegion() != null) {
            wrapper.set(SysSmsProvider::getRegion, entity.getRegion());
        }
        if (entity.getEndpoint() != null) {
            wrapper.set(SysSmsProvider::getEndpoint, entity.getEndpoint());
        }
        if (entity.getEnabled() != null) {
            wrapper.set(SysSmsProvider::getEnabled, entity.getEnabled());
        }
        if (entity.getWeight() != null) {
            wrapper.set(SysSmsProvider::getWeight, entity.getWeight());
        }
        if (entity.getRemark() != null) {
            wrapper.set(SysSmsProvider::getRemark, entity.getRemark());
        }
        wrapper.set(SysSmsProvider::getUpdateTime, LocalDateTime.now());
        wrapper.update();
        smsService.evictProviderCache();
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        providerService.removeById(id);
        smsService.evictProviderCache();
        return Result.success();
    }

    /** 写路径校验：名称/类型必填、类型必须有 SPI 实现、名称唯一。 */
    private void validate(SysSmsProvider entity, Long id) {
        if (entity.getProviderName() == null || entity.getProviderName().isBlank()) {
            throw new BusinessException("SMS012");
        }
        if (entity.getProviderType() == null || entity.getProviderType().isBlank()) {
            throw new BusinessException("SMS011");
        }
        String type = entity.getProviderType().trim().toUpperCase();
        if (providerImpls.stream().noneMatch(p -> p.type().equalsIgnoreCase(type))) {
            throw new BusinessException("SMS006", entity.getProviderType());
        }
        Long dup = providerService.lambdaQuery()
                .eq(SysSmsProvider::getProviderName, entity.getProviderName())
                .ne(id != null, SysSmsProvider::getId, id)
                .count();
        if (dup != null && dup > 0) {
            throw new BusinessException("SMS010", entity.getProviderName());
        }
    }
}
