package com.astral.system.controller;

import java.util.List;
import java.util.Map;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailTemplateService;
import com.astral.system.notify.NotifyChannel;
import com.astral.system.notify.NotifyEventDef;
import com.astral.system.notify.NotifyEventRegistry;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

    /** 发送路径的模板读取走 60s 进程内缓存，写完必须失效 */
    private final MailService mailService;

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
        validateTemplate(entity, true, null);
        templateService.save(entity);
        mailService.evictTemplateCache();
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysMailTemplate entity) {
        entity.setId(id);
        validateTemplate(entity, false, id);
        templateService.updateById(entity);
        mailService.evictTemplateCache();
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.removeById(id);
        mailService.evictTemplateCache();
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

    @Operation(summary = "模板试发（按变量渲染后真实发送一封）")
    @RequiresPermission(PERM_EDIT)
    @PostMapping("/{id}/send")
    public Result<Void> sendTest(@PathVariable Long id, @RequestBody MailTestSendDto dto) {
        mailService.testSendTemplate(id, dto.getAccountId(), dto.getToEmail(), dto.getVariables());
        return Result.success();
    }

    /**
     * 模板写路径校验：scene 必须是 {@link NotifyEventRegistry} 登记的事件、channel 必须在该事件
     * 声明的渠道内、该 (事件 × 渠道) 未被其他模板绑定、variables 必须覆盖事件 payload 全部字段。
     * 事件是代码注册的一等契约（发送侧按事件码解析模板），这里不卡住，后台建的模板就永远是
     * 「没人调用的死数据」。
     *
     * <p>更新时字段级判 null（直调 API 的局部更新只校验传了的字段）；新增时编码/场景/渠道必填。</p>
     */
    private void validateTemplate(SysMailTemplate entity, boolean isCreate, Long id) {
        boolean codePresent = entity.getTemplateCode() != null && !entity.getTemplateCode().isBlank();
        boolean scenePresent = entity.getScene() != null && !entity.getScene().isBlank();
        boolean channelPresent = entity.getChannel() != null && !entity.getChannel().isBlank();
        if (isCreate) {
            if (!codePresent) {
                throw new BusinessException("MAIL015");
            }
            if (!scenePresent) {
                throw new BusinessException("MAIL016");
            }
            if (!channelPresent) {
                throw new BusinessException("MAIL020");
            }
        }
        if (channelPresent && NotifyChannel.mailTemplateChannels().stream().noneMatch(c -> c.equals(entity.getChannel()))) {
            throw new BusinessException("MAIL019", entity.getChannel());
        }
        if (codePresent) {
            Long dup = templateService.lambdaQuery()
                    .eq(SysMailTemplate::getTemplateCode, entity.getTemplateCode())
                    .ne(id != null, SysMailTemplate::getId, id)
                    .count();
            if (dup != null && dup > 0) {
                throw new BusinessException("MAIL008", entity.getTemplateCode());
            }
        }
        if (scenePresent) {
            NotifyEventDef event = NotifyEventRegistry.find(entity.getScene())
                    .orElseThrow(() -> new BusinessException("MAIL009", entity.getScene()));
            if (channelPresent && event.channels().stream().noneMatch(c -> c.equals(entity.getChannel()))) {
                throw new BusinessException("MAIL021", entity.getScene(), entity.getChannel());
            }
            Long bound = templateService.lambdaQuery()
                    .eq(SysMailTemplate::getScene, entity.getScene())
                    .eq(channelPresent, SysMailTemplate::getChannel, entity.getChannel())
                    .ne(id != null, SysMailTemplate::getId, id)
                    .count();
            if (bound != null && bound > 0) {
                throw new BusinessException("MAIL013", entity.getScene());
            }
            List<String> declared = NotifyEventRegistry.parseList(entity.getVariables());
            List<String> missing = event.payloadFieldNames().stream()
                    .filter(v -> !declared.contains(v))
                    .toList();
            if (!missing.isEmpty()) {
                throw new BusinessException("MAIL010", String.join(", ", missing));
            }
        }
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
