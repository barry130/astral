package com.astral.system.controller;

import java.util.List;
import java.util.Map;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.notify.NotifyChannel;
import com.astral.system.notify.NotifyEventDef;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.notify.NotifyPublisher;
import com.astral.system.notify.SysNotifyRuleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 事件订阅规则（阶段④）：事件 × 渠道 → 模板 + 收件人 的 CRUD 与事件发布。
 * 发布入口给管理端做测试与手动触发；业务代码后续直接注入 {@link NotifyPublisher}。
 */
@Tag(name = "事件订阅规则")
@RestController
@RequestMapping("/api/v1/admin/system/notify")
@RequiredArgsConstructor
public class NotifyRuleController {

    private static final String PERM_VIEW = "admin:system:notify:rule:view";
    private static final String PERM_EDIT = "admin:system:notify:rule:edit";

    private final SysNotifyRuleService ruleService;
    private final SysMailTemplateMapper mailTemplateMapper;
    private final SysSmsTemplateMapper smsTemplateMapper;
    private final NotifyPublisher notifyPublisher;

    @Data
    public static class PublishDto {
        private String eventCode;
        /** 事件上下文（payload 字段 → 值） */
        private Map<String, String> variables;
        /** 测试用：非空时替代规则收件人试投 */
        private List<String> recipients;
    }

    @Operation(summary = "分页查询")
    @RequiresPermission(value = PERM_VIEW, name = "订阅规则查看", description = "查看事件订阅规则")
    @GetMapping("/rule/page")
    public Result<Page<SysNotifyRule>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(ruleService.page(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SysNotifyRule>().orderByDesc(SysNotifyRule::getUpdateTime)));
    }

    @Operation(summary = "详情")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/rule/{id}")
    public Result<SysNotifyRule> getById(@PathVariable Long id) {
        return Result.success(ruleService.getById(id));
    }

    @Operation(summary = "新增")
    @RequiresPermission(value = PERM_EDIT, name = "订阅规则维护", description = "维护事件订阅规则与发布测试")
    @PostMapping("/rule")
    public Result<Void> create(@RequestBody SysNotifyRule entity) {
        validateRule(entity, null);
        normalizePlatform(entity);
        ruleService.save(entity);
        return Result.success();
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/rule/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysNotifyRule entity) {
        if (ruleService.getById(id) == null) {
            throw new BusinessException("NOTIFY002", id);
        }
        entity.setId(id);
        validateRule(entity, id);
        normalizePlatform(entity);
        ruleService.updateById(entity);
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/rule/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        ruleService.removeById(id);
        return Result.success();
    }

    @Operation(summary = "发布事件（按启用规则逐条投递，逐规则回报结果）")
    @RequiresPermission(PERM_EDIT)
    @PostMapping("/publish")
    public Result<List<NotifyPublisher.PublishResult>> publish(@RequestBody PublishDto dto) {
        if (dto.getEventCode() == null || dto.getEventCode().isBlank()) {
            throw new BusinessException("NOTIFY009");
        }
        if (!NotifyEventRegistry.exists(dto.getEventCode())) {
            throw new BusinessException("MAIL009", dto.getEventCode());
        }
        return Result.success(notifyPublisher.publish(dto.getEventCode(), dto.getVariables(), dto.getRecipients()));
    }

    /**
     * 规则写路径校验：事件 ∈ 注册表、渠道 ∈ 事件声明、模板与 (事件, 渠道) 一致、
     * 收件人类型/取值合法（PAYLOAD_FIELD 必须是事件 payload 声明过的字段；
     * ROLE 需要角色编码）、平台定向只允许已知平台集合或 all。
     */

    private static final java.util.Set<String> KNOWN_PLATFORMS = java.util.Set.of(
            "all", "app-android", "app-ios", "app-windows", "web", "app", "pc");

    private void validateRule(SysNotifyRule entity, Long id) {
        if (entity.getRuleName() == null || entity.getRuleName().isBlank()) {
            throw new BusinessException("NOTIFY008");
        }
        boolean eventPresent = entity.getEventCode() != null && !entity.getEventCode().isBlank();
        boolean channelPresent = entity.getChannel() != null && !entity.getChannel().isBlank();
        if (entity.getChannel() != null
                && NotifyChannel.registered().stream().noneMatch(c -> c.equalsIgnoreCase(entity.getChannel()))) {
            throw new BusinessException("MAIL019", entity.getChannel());
        }
        boolean roleRecipient = "ROLE".equalsIgnoreCase(entity.getRecipientType());
        boolean payloadRecipient = "PAYLOAD_FIELD".equalsIgnoreCase(entity.getRecipientType());
        if (entity.getRecipientType() != null && !"FIXED".equalsIgnoreCase(entity.getRecipientType())
                && !payloadRecipient && !roleRecipient) {
            throw new BusinessException("NOTIFY005", entity.getRecipientType());
        }
        if (("FIXED".equalsIgnoreCase(entity.getRecipientType()) || roleRecipient)
                && (entity.getRecipientValue() == null || entity.getRecipientValue().isBlank())) {
            throw new BusinessException("NOTIFY006");
        }
        if (entity.getRuleName() != null && !entity.getRuleName().isBlank()) {
            Long dup = ruleService.lambdaQuery()
                    .eq(SysNotifyRule::getRuleName, entity.getRuleName())
                    .ne(id != null, SysNotifyRule::getId, id)
                    .count();
            if (dup != null && dup > 0) {
                throw new BusinessException("NOTIFY001", entity.getRuleName());
            }
        }
        if (!eventPresent) {
            return;
        }
        NotifyEventDef event = NotifyEventRegistry.find(entity.getEventCode())
                .orElseThrow(() -> new BusinessException("MAIL009", entity.getEventCode()));
        if (channelPresent && event.channels().stream().noneMatch(c -> c.equalsIgnoreCase(entity.getChannel()))) {
            throw new BusinessException("MAIL021", entity.getEventCode(), entity.getChannel());
        }
        if (entity.getTemplateId() == null) {
            throw new BusinessException("NOTIFY003");
        }
        if (payloadRecipient && entity.getRecipientValue() != null
                && event.payloadFieldNames().stream().noneMatch(f -> f.equals(entity.getRecipientValue()))) {
            throw new BusinessException("NOTIFY007", entity.getRecipientValue());
        }
        if (entity.getPlatform() != null && !entity.getPlatform().isBlank()) {
            for (String plat : entity.getPlatform().split(",")) {
                String v = plat.trim().toLowerCase();
                if (!v.isEmpty() && !KNOWN_PLATFORMS.contains(v)) {
                    throw new BusinessException("NOTIFY010", plat.trim());
                }
            }
        }
        if (channelPresent) {
            checkTemplateConsistency(entity);
        }
    }

    /** 平台定向归一：小写、去空白（存逗号分隔集合，语义与 sys_notice.channel 一致） */
    private void normalizePlatform(SysNotifyRule entity) {
        if (entity.getPlatform() == null) {
            return;
        }
        String normalized = java.util.Arrays.stream(entity.getPlatform().split(","))
                .map(String::trim).map(String::toLowerCase).filter(s -> !s.isEmpty())
                .distinct().collect(java.util.stream.Collectors.joining(","));
        entity.setPlatform(normalized.isBlank() ? null : normalized);
    }

    /** 模板行必须真实存在且归属 (事件, 渠道)：规则是投递契约，绑错模板等于发错人 */
    private void checkTemplateConsistency(SysNotifyRule entity) {
        String channel = entity.getChannel().toUpperCase();
        if (NotifyChannel.SMS.equals(channel)) {
            SysSmsTemplate tpl = smsTemplateMapper.selectById(entity.getTemplateId());
            if (tpl == null || !entity.getEventCode().equals(tpl.getEventCode())) {
                throw new BusinessException("NOTIFY004", "templateId=" + entity.getTemplateId());
            }
        } else {
            SysMailTemplate tpl = mailTemplateMapper.selectById(entity.getTemplateId());
            boolean matches = tpl != null
                    && channel.equalsIgnoreCase(tpl.getChannel())
                    && entity.getEventCode().equals(tpl.getScene());
            if (!matches) {
                throw new BusinessException("NOTIFY004", "templateId=" + entity.getTemplateId());
            }
        }
    }
}
