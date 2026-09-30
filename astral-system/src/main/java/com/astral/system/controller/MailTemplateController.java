package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.system.mail.SysMailTemplateService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "邮件模板")
@RestController
@RequestMapping("/api/v1/admin/system/mail/template")
@RequiredArgsConstructor
public class MailTemplateController {

    /** 查看权限（sys_permission: admin:system:mail:view） */
    private static final String PERM_VIEW = "admin:system:mail:view";
    /** 维护权限（sys_permission: admin:system:mail:template:edit） */
    private static final String PERM_EDIT = "admin:system:mail:template:edit";

    private final SysMailTemplateService templateService;

    @Operation(summary = "分页查询")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/page")
    public Result<Page<SysMailTemplate>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(templateService.page(new Page<>(pageNum, pageSize)));
    }

    @Operation(summary = "详情")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/{id}")
    public Result<SysMailTemplate> getById(@PathVariable Long id) {
        return Result.success(templateService.getById(id));
    }

    @Operation(summary = "新增")
    @RequiresPermission(PERM_EDIT)
    @PostMapping
    public Result<Void> create(@RequestBody SysMailTemplate entity) {
        templateService.save(entity);
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMailTemplate entity) {
        entity.setId(id);
        templateService.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.removeById(id);
        return Result.success();
    }

    @Operation(summary = "模板预览（按变量渲染）")
    @RequiresPermission(PERM_VIEW)
    @PostMapping("/preview")
    public Result<String> preview(@RequestBody MailPreviewDto dto) {
        SysMailTemplate tpl = templateService.getById(dto.getTemplateId());
        if (tpl == null) {
            return Result.fail("模板不存在");
        }
        return Result.success(render(tpl.getContent(), dto.getVariables()));
    }

    private String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        String r = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                r = r.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
            }
        }
        return r;
    }
}
