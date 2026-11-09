package com.astral.system.notify;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysNotifyRuleChannel;
import com.astral.dao.entity.SysNotifyRuleRecipient;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.dto.NotifyRuleSaveDto;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class SysNotifyRuleServiceImpl extends ServiceImpl<SysNotifyRuleMapper, SysNotifyRule>
        implements SysNotifyRuleService {
    private static final Set<String> KNOWN_PLATFORMS = Set.of(
            "all",
            "app-android",
            "app-ios",
            "app-windows",
            "app-linux",
            "app-macos",
            "app-harmony",
            "web",
            "app",
            "pc");
    private static final Set<String> RECIPIENT_TYPES = Set.of("FIXED", "PAYLOAD_FIELD", "ROLE");

    private final SysNotifyRuleChannelMapper ruleChannelMapper;
    private final SysNotifyRuleRecipientMapper ruleRecipientMapper;
    private final SysMailTemplateMapper mailTemplateMapper;
    private final SysSmsTemplateMapper smsTemplateMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createRule(NotifyRuleSaveDto dto) {
        NotifyEventDef event = validate(dto, null);
        SysNotifyRule rule = new SysNotifyRule();
        rule.setRuleName(dto.getRuleName().trim());
        rule.setEventCode(dto.getEventCode().trim());
        rule.setRemark(dto.getRemark());
        rule.setEnabled(dto.getEnabled() == null ? 1 : dto.getEnabled());
        if (!save(rule)) throw BusinessException.of("通知规则保存失败");
        persistChannels(rule.getId(), dto, event);
        return rule.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRule(Long id, NotifyRuleSaveDto dto) {
        if (lockRule(id) == null) throw new BusinessException("NOTIFY002", id);
        NotifyEventDef event = validate(dto, id);
        boolean saved = update(new LambdaUpdateWrapper<SysNotifyRule>()
                .eq(SysNotifyRule::getId, id)
                .set(SysNotifyRule::getRuleName, dto.getRuleName().trim())
                .set(SysNotifyRule::getEventCode, dto.getEventCode().trim())
                .set(SysNotifyRule::getRemark, dto.getRemark())
                .set(SysNotifyRule::getEnabled, dto.getEnabled() == null ? 1 : dto.getEnabled())
                .set(SysNotifyRule::getUpdateTime, java.time.LocalDateTime.now()));
        if (!saved) throw new BusinessException("NOTIFY002", id);
        persistChannels(id, dto, event);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long id) {
        if (lockRule(id) == null) return;
        List<SysNotifyRuleChannel> channels = ruleChannelMapper.selectList(
                new LambdaQueryWrapper<SysNotifyRuleChannel>().eq(SysNotifyRuleChannel::getRuleId, id));
        channels.forEach(ch -> ruleRecipientMapper.delete(
                new LambdaQueryWrapper<SysNotifyRuleRecipient>().eq(SysNotifyRuleRecipient::getChannelId, ch.getId())));
        ruleChannelMapper.delete(
                new LambdaQueryWrapper<SysNotifyRuleChannel>().eq(SysNotifyRuleChannel::getRuleId, id));
        removeById(id);
    }

    private SysNotifyRule lockRule(Long id) {
        // All existing-rule writers lock the parent before reading or changing channels and recipients.
        return lambdaQuery().eq(SysNotifyRule::getId, id).last("FOR UPDATE").one();
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
        Long dup = this.lambdaQuery()
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
            String type = recipient.getType() == null
                    ? ""
                    : recipient.getType().trim().toUpperCase(Locale.ROOT);
            if (!RECIPIENT_TYPES.contains(type)) {
                throw new BusinessException("NOTIFY005", recipient.getType());
            }
            if (!StringUtils.hasText(recipient.getValue())) {
                throw new BusinessException("NOTIFY006");
            }
            if ("PAYLOAD_FIELD".equals(type)
                    && event.payloadFieldNames().stream()
                            .noneMatch(f -> f.equals(recipient.getValue().trim()))) {
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
                .collect(Collectors.toMap(
                        c -> c.getChannel().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
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
                if (ruleChannelMapper.insert(row) != 1) throw BusinessException.of("通知渠道保存失败");
            } else {
                int changed = ruleChannelMapper.update(
                        null,
                        new LambdaUpdateWrapper<SysNotifyRuleChannel>()
                                .eq(SysNotifyRuleChannel::getId, row.getId())
                                .eq(SysNotifyRuleChannel::getRuleId, ruleId)
                                .set(SysNotifyRuleChannel::getTemplateId, row.getTemplateId())
                                .set(SysNotifyRuleChannel::getEnabled, row.getEnabled())
                                .set(SysNotifyRuleChannel::getDailyLimit, row.getDailyLimit())
                                .set(SysNotifyRuleChannel::getDailyLimitScope, row.getDailyLimitScope())
                                .set(SysNotifyRuleChannel::getPlatform, row.getPlatform())
                                .set(SysNotifyRuleChannel::getUpdateTime, java.time.LocalDateTime.now()));
                if (changed != 1) throw BusinessException.of("通知渠道已变更，请刷新后重试");
            }
            // 收件人整体重建（数量少、语义简单：提交什么就是什么）
            ruleRecipientMapper.delete(new LambdaQueryWrapper<SysNotifyRuleRecipient>()
                    .eq(SysNotifyRuleRecipient::getChannelId, row.getId()));
            for (NotifyRuleSaveDto.RecipientDto recipient : channel.getRecipients()) {
                SysNotifyRuleRecipient line = new SysNotifyRuleRecipient();
                line.setChannelId(row.getId());
                line.setRecipientType(recipient.getType().trim().toUpperCase(Locale.ROOT));
                line.setRecipientValue(recipient.getValue().trim());
                if (ruleRecipientMapper.insert(line) != 1) throw BusinessException.of("通知收件人保存失败");
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
                .map(String::trim)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .collect(Collectors.joining(","));
        return normalized.isBlank() ? null : normalized;
    }
}
