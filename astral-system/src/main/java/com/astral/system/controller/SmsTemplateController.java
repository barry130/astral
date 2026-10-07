package com.astral.system.controller;

import java.util.List;
import java.util.Map;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.system.notify.NotifyChannel;
import com.astral.system.notify.NotifyEventDef;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.notify.SmsService;
import com.astral.system.notify.SysSmsTemplateService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 短信模板管理。与邮件模板的本质差异：短信正文在供应商侧备案审核，本地只存
 * 「事件 → 供应商模板编码 + 签名 + 审核状态」的映射，不渲染、没有 variables 声明
 * （事件 payload 字段名即变量名，透传给供应商）。
 */
@Tag(name = "短信模板")
@RestController
@RequestMapping("/api/v1/admin/system/sms/template")
@RequiredArgsConstructor
public class SmsTemplateController {

    private static final String PERM_VIEW = "admin:system:notify:sms:view";
    private static final String PERM_EDIT = "admin:system:notify:sms:edit";

    private final SysSmsTemplateService templateService;
    private final SmsService smsService;

    @Data
    public static class SmsTestSendDto {
        /** 发送手机号（必填） */
        private String phone;
        /** 供应商ID；不传则按权重在启用供应商中自动选择 */
        private Long providerId;
        /** 模板变量值（按事件 payload 字段名传入） */
        private Map<String, String> variables;
    }

    @Operation(summary = "分页查询")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/page")
    public Result<Page<SysSmsTemplate>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                             @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(templateService.page(new Page<>(pageNum, pageSize)));
    }

    @Operation(summary = "详情")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/{id}")
    public Result<SysSmsTemplate> getById(@PathVariable Long id) {
        return Result.success(templateService.getById(id));
    }

    @Operation(summary = "新增")
    @RequiresPermission(PERM_EDIT)
    @PostMapping
    public Result<Void> create(@RequestBody SysSmsTemplate entity) {
        validateTemplate(entity, true, null);
        templateService.save(entity);
        smsService.evictTemplateCache();
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysSmsTemplate entity) {
        entity.setId(id);
        validateTemplate(entity, false, id);
        templateService.updateById(entity);
        smsService.evictTemplateCache();
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.removeById(id);
        smsService.evictTemplateCache();
        return Result.success();
    }

    @Operation(summary = "模板试发（真实调用供应商发一条）")
    @RequiresPermission(PERM_EDIT)
    @PostMapping("/{id}/send")
    public Result<Void> sendTest(@PathVariable Long id, @RequestBody SmsTestSendDto dto) {
        smsService.testSendTemplate(id, dto.getProviderId(), dto.getPhone(), dto.getVariables());
        return Result.success();
    }

    /**
     * 写路径校验（事件契约与邮件模板同一套语义）：
     * eventCode 必须在 {@link NotifyEventRegistry} 登记、事件必须声明 SMS 渠道、
     * 同事件只允许绑定一个短信模板、编码唯一、审核状态 ∈ 0..3。
     * 供应商模板编码必填——本地没有正文，没有它发送无从谈起。
     */
    private void validateTemplate(SysSmsTemplate entity, boolean isCreate, Long id) {
        boolean codePresent = entity.getTemplateCode() != null && !entity.getTemplateCode().isBlank();
        boolean eventPresent = entity.getEventCode() != null && !entity.getEventCode().isBlank();
        if (isCreate) {
            if (!codePresent) {
                throw new BusinessException("SMS013");
            }
            if (entity.getTemplateName() == null || entity.getTemplateName().isBlank() || !eventPresent) {
                throw new BusinessException("SMS014");
            }
            if (entity.getProviderTemplateCode() == null || entity.getProviderTemplateCode().isBlank()) {
                throw new BusinessException("SMS015");
            }
        }
        if (entity.getAuditStatus() != null && (entity.getAuditStatus() < 0 || entity.getAuditStatus() > 3)) {
            throw new BusinessException("SMS016");
        }
        if (codePresent) {
            Long dup = templateService.lambdaQuery()
                    .eq(SysSmsTemplate::getTemplateCode, entity.getTemplateCode())
                    .ne(id != null, SysSmsTemplate::getId, id)
                    .count();
            if (dup != null && dup > 0) {
                throw new BusinessException("SMS007", entity.getTemplateCode());
            }
        }
        if (eventPresent) {
            NotifyEventDef event = NotifyEventRegistry.find(entity.getEventCode())
                    .orElseThrow(() -> new BusinessException("MAIL009", entity.getEventCode()));
            if (event.channels().stream().noneMatch(NotifyChannel.SMS::equals)) {
                throw new BusinessException("MAIL021", entity.getEventCode(), NotifyChannel.SMS);
            }
            Long bound = templateService.lambdaQuery()
                    .eq(SysSmsTemplate::getEventCode, entity.getEventCode())
                    .ne(id != null, SysSmsTemplate::getId, id)
                    .count();
            if (bound != null && bound > 0) {
                throw new BusinessException("SMS008", entity.getEventCode());
            }
        }
    }
}
