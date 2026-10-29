package com.astral.system.controller;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysNotifyRuleChannel;
import com.astral.dao.entity.SysNotifyRuleRecipient;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.dto.NotifyEventVo;
import com.astral.system.dto.NotifyRuleSaveDto;
import com.astral.system.dto.NotifyRuleVo;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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
 * 事件订阅规则（阶段④）：事件 × 渠道 → 模板 + 收件人 的 CRUD 与事件发布。
 *
 * <p>存储拆成三张表：{@code sys_notify_rule}（规则：事件 + 开关）/
 * {@code sys_notify_rule_channel}（渠道：模板 + 限额 + 平台定向）/
 * {@code sys_notify_rule_recipient}（收件人：类型 + 取值）。一条规则可挂多渠道，
 * 一个渠道可配多个收件人，因此「同一事件建多条规则」「同事件多渠道同时生效」都成立。</p>
 *
 * <p>发布入口给管理端做测试与手动触发；业务代码直接注入 {@link NotifyPublisher}。</p>
 */
@Tag(name = "事件订阅规则")
@RestController
@RequestMapping("/api/v1/admin/system/notify")
@RequiredArgsConstructor
public class NotifyRuleController {

    private static final String PERM_VIEW = "admin:system:notify:rule:view";
    private static final String PERM_EDIT = "admin:system:notify:rule:edit";

    private static final Set<String> KNOWN_PLATFORMS = Set.of(
            "all", "app-android", "app-ios", "app-windows", "app-linux", "app-macos",
            "app-harmony", "web", "app", "pc");

    private static final Set<String> RECIPIENT_TYPES = Set.of("FIXED", "PAYLOAD_FIELD", "ROLE");

    private final SysNotifyRuleService ruleService;
    private final SysNotifyRuleChannelMapper ruleChannelMapper;
    private final SysNotifyRuleRecipientMapper ruleRecipientMapper;
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

    @Operation(summary = "事件列表（代码注册表，前端「绑定事件」下拉与变量补全的数据源）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/event")
    public Result<List<NotifyEventVo>> events() {
        List<NotifyEventVo> list = NotifyEventRegistry.list().stream().map(this::toEventVo).toList();
        return Result.success(list);
    }

    @Operation(summary = "按事件 + 渠道查询可选模板（规则编辑器的模板下拉数据源，返回模板 id）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/template")
    public Result<java.util.List<java.util.Map<String, Object>>> templates(@RequestParam String eventCode,
                                                                           @RequestParam String channel) {
        String ch = channel == null ? "" : channel.trim().toUpperCase();
        java.util.List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        if ("SMS".equals(ch)) {
            smsTemplateMapper.selectList(new LambdaQueryWrapper<SysSmsTemplate>()
                            .eq(SysSmsTemplate::getEventCode, eventCode)
                            .orderByAsc(SysSmsTemplate::getId))
                    .forEach(t -> out.add(templateOption(t.getId(), t.getTemplateCode(), t.getTemplateName())));
        } else if ("EMAIL".equals(ch) || "INAPP".equals(ch)) {
            mailTemplateMapper.selectList(new LambdaQueryWrapper<SysMailTemplate>()
                            .eq(SysMailTemplate::getScene, eventCode)
                            .eq(SysMailTemplate::getChannel, ch)
                            .orderByAsc(SysMailTemplate::getId))
                    .forEach(t -> out.add(templateOption(t.getId(), t.getTemplateCode(), t.getTemplateName())));
        } else {
            throw new BusinessException("NOTIFY010", channel);
        }
        return Result.success(out);
    }

    private java.util.Map<String, Object> templateOption(Long id, String code, String name) {
        java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        row.put("templateCode", code);
        row.put("templateName", name);
        return row;
    }

    @Operation(summary = "分页查询")
    @RequiresPermission(value = PERM_VIEW, name = "订阅规则查看", description = "查看事件订阅规则")
    @GetMapping("/rule/page")
    public Result<Page<NotifyRuleVo>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                           @RequestParam(defaultValue = "10") Integer pageSize,
                                           @RequestParam(required = false) String eventCode,
                                           @RequestParam(required = false) Integer enabled,
                                           @RequestParam(required = false) String keyword) {
        Page<SysNotifyRule> page = ruleService.page(new Page<>(pageNum, pageSize),
                new LambdaQueryWrapper<SysNotifyRule>()
                        .eq(StringUtils.hasText(eventCode), SysNotifyRule::getEventCode, eventCode)
                        .eq(enabled != null, SysNotifyRule::getEnabled, enabled)
                        .like(StringUtils.hasText(keyword), SysNotifyRule::getRuleName, keyword)
                        .orderByDesc(SysNotifyRule::getUpdateTime));
        Page<NotifyRuleVo> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(toVoList(page.getRecords()));
        return Result.success(out);
    }

    @Operation(summary = "详情")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/rule/{id}")
    public Result<NotifyRuleVo> getById(@PathVariable Long id) {
        SysNotifyRule rule = ruleService.getById(id);
        if (rule == null) {
            throw new BusinessException("NOTIFY002", id);
        }
        return Result.success(toVoList(List.of(rule)).get(0));
    }

    @Operation(summary = "新增")
    @RequiresPermission(value = PERM_EDIT, name = "订阅规则维护", description = "维护事件订阅规则与发布测试")
    @PostMapping("/rule")
    @Transactional
    public Result<Long> create(@RequestBody NotifyRuleSaveDto dto) {
        NotifyEventDef event = validate(dto, null);
        SysNotifyRule rule = new SysNotifyRule();
        rule.setRuleName(dto.getRuleName().trim());
        rule.setEventCode(dto.getEventCode().trim());
        rule.setRemark(dto.getRemark());
        rule.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        ruleService.save(rule);
        persistChannels(rule.getId(), dto, event);
        return Result.success(rule.getId());
    }

    @Operation(summary = "更新")
    @RequiresPermission(PERM_EDIT)
    @PutMapping("/rule/{id}")
    @Transactional
    public Result<Void> update(@PathVariable Long id, @RequestBody NotifyRuleSaveDto dto) {
        if (ruleService.getById(id) == null) {
            throw new BusinessException("NOTIFY002", id);
        }
        NotifyEventDef event = validate(dto, id);
        SysNotifyRule rule = new SysNotifyRule();
        rule.setId(id);
        rule.setRuleName(dto.getRuleName().trim());
        rule.setEventCode(dto.getEventCode().trim());
        rule.setRemark(dto.getRemark());
        rule.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        ruleService.updateById(rule);
        persistChannels(id, dto, event);
        return Result.success();
    }

    @Operation(summary = "删除")
    @RequiresPermission(PERM_EDIT)
    @DeleteMapping("/rule/{id}")
    @Transactional
    public Result<Void> delete(@PathVariable Long id) {
        List<SysNotifyRuleChannel> channels = ruleChannelMapper.selectList(
                new LambdaQueryWrapper<SysNotifyRuleChannel>().eq(SysNotifyRuleChannel::getRuleId, id));
        channels.forEach(ch -> ruleRecipientMapper.delete(new LambdaQueryWrapper<SysNotifyRuleRecipient>()
                .eq(SysNotifyRuleRecipient::getChannelId, ch.getId())));
        ruleChannelMapper.delete(new LambdaQueryWrapper<SysNotifyRuleChannel>()
                .eq(SysNotifyRuleChannel::getRuleId, id));
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

    // ==================== 写路径校验 ====================

    /**
     * 规则写路径校验：事件 ∈ 注册表、渠道 ∈ 事件声明、模板与 (事件, 渠道) 一致、
     * 收件人类型/取值合法（PAYLOAD_FIELD 必须是事件 payload 声明过的字段；ROLE 需要角色编码）、
     * 平台定向只允许已知平台集合、事件必达渠道必须保留。
     */
    private NotifyEventDef validate(NotifyRuleSaveDto dto, Long id) {
        if (!StringUtils.hasText(dto.getRuleName())) {
            throw new BusinessException("NOTIFY008");
        }
        if (!StringUtils.hasText(dto.getEventCode())) {
            throw new BusinessException("NOTIFY009");
        }
        Long dup = ruleService.lambdaQuery()
                .eq(SysNotifyRule::getRuleName, dto.getRuleName().trim())
                .ne(id != null, SysNotifyRule::getId, id)
                .count();
        if (dup != null && dup > 0) {
            throw new BusinessException("NOTIFY001", dto.getRuleName());
        }
        NotifyEventDef event = NotifyEventRegistry.find(dto.getEventCode().trim())
                .orElseThrow(() -> new BusinessException("MAIL009", dto.getEventCode()));
        if (dto.getChannels() == null || dto.getChannels().isEmpty()) {
            throw new BusinessException("NOTIFY011");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (NotifyRuleSaveDto.ChannelDto channel : dto.getChannels()) {
            if (!StringUtils.hasText(channel.getChannel())) {
                throw new BusinessException("MAIL020");
            }
            String code = channel.getChannel().trim().toUpperCase(Locale.ROOT);
            if (!NotifyChannel.registered().contains(code)) {
                throw new BusinessException("MAIL019", channel.getChannel());
            }
            if (!seen.add(code)) {
                throw new BusinessException("MAIL019", code + "（重复渠道）");
            }
            if (event.channels().stream().noneMatch(c -> c.equalsIgnoreCase(code))) {
                throw new BusinessException("MAIL021", event.code(), code);
            }
            validateRecipients(event, code, channel);
            if (channel.getTemplateId() != null) {
                checkTemplateConsistency(event, code, channel.getTemplateId());
            }
            if (StringUtils.hasText(channel.getPlatform())) {
                for (String plat : channel.getPlatform().split(",")) {
                    String v = plat.trim().toLowerCase(Locale.ROOT);
                    if (!v.isEmpty() && !KNOWN_PLATFORMS.contains(v)) {
                        throw new BusinessException("NOTIFY010", plat.trim());
                    }
                }
            }
        }
        for (String required : event.requiredChannels()) {
            boolean present = dto.getChannels().stream()
                    .filter(c -> StringUtils.hasText(c.getChannel()))
                    .anyMatch(c -> c.getChannel().trim().equalsIgnoreCase(required)
                            && (c.getEnabled() == null || c.getEnabled() != 0));
            if (!present) {
                throw new BusinessException("NOTIFY012", required);
            }
        }
        return event;
    }

    private void validateRecipients(NotifyEventDef event, String channelCode, NotifyRuleSaveDto.ChannelDto channel) {
        if (channel.getRecipients() == null || channel.getRecipients().isEmpty()) {
            throw new BusinessException("NOTIFY013", channelCode);
        }
        for (NotifyRuleSaveDto.RecipientDto recipient : channel.getRecipients()) {
            String type = recipient.getType() == null ? "" : recipient.getType().trim().toUpperCase(Locale.ROOT);
            if (!RECIPIENT_TYPES.contains(type)) {
                throw new BusinessException("NOTIFY005", recipient.getType());
            }
            if (!StringUtils.hasText(recipient.getValue())) {
                throw new BusinessException("NOTIFY006");
            }
            if ("PAYLOAD_FIELD".equals(type)
                    && event.payloadFieldNames().stream().noneMatch(f -> f.equals(recipient.getValue().trim()))) {
                throw new BusinessException("NOTIFY007", recipient.getValue());
            }
        }
    }

    /** 模板行必须真实存在且归属 (事件, 渠道)：规则是投递契约，绑错模板等于发错人 */
    private void checkTemplateConsistency(NotifyEventDef event, String channelCode, Long templateId) {
        if (NotifyChannel.SMS.equals(channelCode)) {
            SysSmsTemplate tpl = smsTemplateMapper.selectById(templateId);
            if (tpl == null || !event.code().equals(tpl.getEventCode())) {
                throw new BusinessException("NOTIFY004", "templateId=" + templateId);
            }
            return;
        }
        SysMailTemplate tpl = mailTemplateMapper.selectById(templateId);
        boolean matches = tpl != null
                && channelCode.equalsIgnoreCase(tpl.getChannel())
                && event.code().equals(tpl.getScene());
        if (!matches) {
            throw new BusinessException("NOTIFY004", "templateId=" + templateId);
        }
    }

    // ==================== 渠道 / 收件人落库 ====================

    /** 渠道差量重建：已移除的渠道及其收件人删除；提交的渠道更新并整体重建收件人 */
    private void persistChannels(Long ruleId, NotifyRuleSaveDto dto, NotifyEventDef event) {
        List<SysNotifyRuleChannel> existing = ruleChannelMapper.selectList(
                new LambdaQueryWrapper<SysNotifyRuleChannel>().eq(SysNotifyRuleChannel::getRuleId, ruleId));
        Map<String, SysNotifyRuleChannel> byCode = existing.stream()
                .collect(Collectors.toMap(c -> c.getChannel().toUpperCase(Locale.ROOT), Function.identity(),
                        (a, b) -> a));
        Set<String> keep = new LinkedHashSet<>();
        for (NotifyRuleSaveDto.ChannelDto channel : dto.getChannels()) {
            String code = channel.getChannel().trim().toUpperCase(Locale.ROOT);
            keep.add(code);
            SysNotifyRuleChannel row = byCode.get(code);
            boolean isNew = row == null;
            if (isNew) {
                row = new SysNotifyRuleChannel();
                row.setRuleId(ruleId);
                row.setChannel(code);
            }
            row.setTemplateId(channel.getTemplateId());
            row.setEnabled(channel.getEnabled() == null ? 1 : channel.getEnabled());
            row.setDailyLimit(channel.getDailyLimit() == null ? 0 : channel.getDailyLimit());
            row.setDailyLimitScope(normalizeScope(channel.getLimitScope()));
            row.setPlatform(normalizePlatform(event, code, channel.getPlatform()));
            if (isNew) {
                ruleChannelMapper.insert(row);
            } else {
                ruleChannelMapper.updateById(row);
            }
            // 收件人整体重建（数量少、语义简单：提交什么就是什么）
            ruleRecipientMapper.delete(new LambdaQueryWrapper<SysNotifyRuleRecipient>()
                    .eq(SysNotifyRuleRecipient::getChannelId, row.getId()));
            for (NotifyRuleSaveDto.RecipientDto recipient : channel.getRecipients()) {
                SysNotifyRuleRecipient line = new SysNotifyRuleRecipient();
                line.setChannelId(row.getId());
                line.setRecipientType(recipient.getType().trim().toUpperCase(Locale.ROOT));
                line.setRecipientValue(recipient.getValue().trim());
                ruleRecipientMapper.insert(line);
            }
        }
        existing.stream()
                .filter(ch -> !keep.contains(ch.getChannel().toUpperCase(Locale.ROOT)))
                .forEach(ch -> {
                    ruleRecipientMapper.delete(new LambdaQueryWrapper<SysNotifyRuleRecipient>()
                            .eq(SysNotifyRuleRecipient::getChannelId, ch.getId()));
                    ruleChannelMapper.deleteById(ch.getId());
                });
    }

    /** 限额口径归一：只认 RECIPIENT / GLOBAL，其余按收件人 */
    private String normalizeScope(String scope) {
        return "GLOBAL".equalsIgnoreCase(scope) ? "GLOBAL" : "RECIPIENT";
    }

    /**
     * 平台定向归一：小写、去空白（存逗号分隔集合，语义与 sys_notice.channel 一致）。
     * 反馈双方回复类事件的站内信跨平台可见，固定 all。
     */
    private String normalizePlatform(NotifyEventDef event, String channelCode, String platform) {
        if (NotifyChannel.INAPP.equals(channelCode) && NotifyEventRegistry.isFeedbackReply(event.code())) {
            return "all";
        }
        if (!StringUtils.hasText(platform)) {
            return null;
        }
        String normalized = java.util.Arrays.stream(platform.split(","))
                .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .distinct().collect(Collectors.joining(","));
        return normalized.isBlank() ? null : normalized;
    }

    // ==================== 出参组装 ====================

    private NotifyEventVo toEventVo(NotifyEventDef def) {
        NotifyEventVo vo = new NotifyEventVo();
        vo.setCode(def.code());
        vo.setName(def.name());
        vo.setDescription(def.description());
        vo.setChannels(def.channels());
        vo.setRequiredChannels(def.requiredChannels());
        vo.setPayloadKeys(def.payloadFieldNames());
        vo.setPayloadFields(def.payloadFields().stream().map(f -> {
            NotifyEventVo.FieldVo field = new NotifyEventVo.FieldVo();
            field.setName(f.name());
            field.setDescription(f.description());
            return field;
        }).toList());
        return vo;
    }

    /** 批量组装：一次查完渠道与收件人，避免分页 N+1 */
    private List<NotifyRuleVo> toVoList(List<SysNotifyRule> rules) {
        if (rules == null || rules.isEmpty()) {
            return List.of();
        }
        List<Long> ruleIds = rules.stream().map(SysNotifyRule::getId).toList();
        List<SysNotifyRuleChannel> channels = ruleChannelMapper.selectList(
                new LambdaQueryWrapper<SysNotifyRuleChannel>().in(SysNotifyRuleChannel::getRuleId, ruleIds));
        List<Long> channelIds = channels.stream().map(SysNotifyRuleChannel::getId).toList();
        Map<Long, List<SysNotifyRuleRecipient>> recipientsByChannel = channelIds.isEmpty() ? Map.of()
                : ruleRecipientMapper.selectList(new LambdaQueryWrapper<SysNotifyRuleRecipient>()
                        .in(SysNotifyRuleRecipient::getChannelId, channelIds))
                .stream().collect(Collectors.groupingBy(SysNotifyRuleRecipient::getChannelId));
        Map<Long, List<SysNotifyRuleChannel>> channelsByRule = channels.stream()
                .collect(Collectors.groupingBy(SysNotifyRuleChannel::getRuleId));
        List<NotifyRuleVo> out = new ArrayList<>(rules.size());
        for (SysNotifyRule rule : rules) {
            NotifyRuleVo vo = new NotifyRuleVo();
            vo.setId(rule.getId());
            vo.setRuleName(rule.getRuleName());
            vo.setEventCode(rule.getEventCode());
            vo.setEventName(NotifyEventRegistry.find(rule.getEventCode())
                    .map(NotifyEventDef::name).orElse(rule.getEventCode()));
            vo.setRemark(rule.getRemark());
            vo.setEnabled(rule.getEnabled());
            vo.setUpdateTime(rule.getUpdateTime());
            List<NotifyRuleVo.ChannelVo> channelVos = new ArrayList<>();
            Set<String> platforms = new LinkedHashSet<>();
            for (SysNotifyRuleChannel channel : channelsByRule.getOrDefault(rule.getId(), List.of())) {
                NotifyRuleVo.ChannelVo cv = new NotifyRuleVo.ChannelVo();
                cv.setId(channel.getId());
                cv.setChannel(channel.getChannel());
                cv.setTemplateId(channel.getTemplateId());
                cv.setTemplateName(templateName(channel));
                cv.setEnabled(channel.getEnabled());
                cv.setDailyLimit(channel.getDailyLimit());
                cv.setLimitScope(channel.getDailyLimitScope() == null ? "RECIPIENT" : channel.getDailyLimitScope());
                cv.setPlatform(channel.getPlatform());
                if (StringUtils.hasText(channel.getPlatform())) {
                    for (String p : channel.getPlatform().split(",")) {
                        if (!p.isBlank()) {
                            platforms.add(p.trim());
                        }
                    }
                }
                cv.setRecipients(recipientsByChannel.getOrDefault(channel.getId(), List.of()).stream().map(r -> {
                    NotifyRuleVo.RecipientVo rv = new NotifyRuleVo.RecipientVo();
                    rv.setType(r.getRecipientType());
                    rv.setValue(r.getRecipientValue());
                    return rv;
                }).toList());
                channelVos.add(cv);
            }
            vo.setChannels(channelVos);
            vo.setPlatforms(new ArrayList<>(platforms));
            out.add(vo);
        }
        return out;
    }

    private String templateName(SysNotifyRuleChannel channel) {
        if (channel.getTemplateId() == null) {
            return null;
        }
        if (NotifyChannel.SMS.equalsIgnoreCase(channel.getChannel())) {
            SysSmsTemplate tpl = smsTemplateMapper.selectById(channel.getTemplateId());
            return tpl == null ? null : tpl.getTemplateName();
        }
        SysMailTemplate tpl = mailTemplateMapper.selectById(channel.getTemplateId());
        return tpl == null ? null : tpl.getTemplateName();
    }
}
