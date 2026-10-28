package com.astral.system.controller;

import java.util.List;
import java.util.Map;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.system.dto.MessageTemplateSaveDto;
import com.astral.system.dto.MessageTemplateVo;
import com.astral.system.notify.MessageTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息模板（统一管理邮箱模板 / 站内信模板 / 短信模板）。
 *
 * <p>原「邮箱模板」「短信模板」两个页面合并为这一页：一条模板由「编码 + 绑定事件 +
 * 渠道多选」组成，勾了哪些渠道就维护哪些渠道的内容（邮件主题/HTML 与短信签名/供应商模板码
 * 同表单，按勾选显隐）。底层仍是渠道分行，发送侧按 (事件, 渠道) 取模板，接口契约不变。</p>
 */
@Tag(name = "消息模板")
@RestController
@RequestMapping("/api/v1/admin/system/message/template")
@RequiredArgsConstructor
public class MessageTemplateController {

    /** 查看权限（复用邮件模块，合并后不再单独建权限码） */
    private static final String PERM_VIEW = "admin:system:mail:view";
    /** 维护权限（复用邮件模板维护权） */
    private static final String PERM_EDIT = "admin:system:mail:template:edit";

    private final MessageTemplateService service;

    @Data
    public static class MessagePreviewDto {
        /** 模板正文（HTML） */
        private String content;
        /** 变量值 */
        private Map<String, String> variables;
    }

    @Operation(summary = "模板列表（按 事件+编码 聚合渠道）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/list")
    public Result<List<MessageTemplateVo>> list() {
        return Result.success(service.list());
    }

    @Operation(summary = "事件下拉（含可用渠道与变量字段）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/options")
    public Result<List<Map<String, Object>>> options() {
        return Result.success(service.eventOptions());
    }

    @Operation(summary = "新增或更新（channels 为期望渠道集合，取消勾选的渠道会被删除）")
    @RequiresPermission(PERM_EDIT)
    @PostMapping("/save")
    public Result<Void> save(@RequestBody MessageTemplateSaveDto dto) {
        service.save(dto);
        return Result.success();
    }

    @Operation(summary = "删除整条模板（该编码在绑定事件下的全部渠道行）")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping
    public Result<Void> delete(@RequestParam String templateCode, @RequestParam String scene) {
        service.delete(templateCode, scene);
        return Result.success();
    }

    @Operation(summary = "正文预览（按变量渲染）")
    @RequiresPermission(PERM_VIEW)
    @PostMapping("/preview")
    public Result<String> preview(@RequestBody MessagePreviewDto dto) {
        return Result.success(service.preview(dto.getContent(), dto.getVariables()));
    }
}
