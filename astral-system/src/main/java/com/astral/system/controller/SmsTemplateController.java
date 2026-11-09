package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.system.notify.SmsService;
import com.astral.system.notify.SysSmsTemplateService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    public Result<Page<SysSmsTemplate>> page(
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize) {
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
    public Result<Void> create(@Valid @RequestBody SysSmsTemplate entity) {
        templateService.createTemplate(entity);
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody SysSmsTemplate entity) {
        templateService.updateTemplate(id, entity);
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return Result.success();
    }

    @Operation(summary = "模板试发（真实调用供应商发一条）")
    @RequiresPermission(PERM_EDIT)
    @PostMapping("/{id}/send")
    public Result<Void> sendTest(@PathVariable Long id, @RequestBody SmsTestSendDto dto) {
        smsService.testSendTemplate(id, dto.getProviderId(), dto.getPhone(), dto.getVariables());
        return Result.success();
    }
}
