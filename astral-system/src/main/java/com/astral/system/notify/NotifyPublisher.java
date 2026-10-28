package com.astral.system.notify;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.astral.dao.entity.Role;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysNotifyRuleChannel;
import com.astral.dao.entity.SysNotifyRuleRecipient;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.mail.MailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 事件发布器 + 通知投递执行器（阶段④）：把「事件 → 渠道投递」从调用方代码里解耦出来。
 *
 * <p>调用方只管在业务节点 {@code publish(eventCode, payload)}；发给谁、走什么渠道、
 * 用什么模板、限额多少，全由「事件订阅规则」决定（本表 {@code sys_notify_rule} 拆成
 * 规则 / 渠道 / 收件人 三张表：一条规则可挂多渠道，一个渠道可配多个收件人）。</p>
 *
 * <p>投递顺序：规则（启用）→ 渠道（启用）→ 收件人 → 单次投递。逐条隔离：
 * 单条失败不影响其余，结果逐条回报（管理端发布测试直接展示，业务侧异步只记日志）。</p>
 *
 * <p>收件人三种来源：{@code FIXED}（写死）/ {@code PAYLOAD_FIELD}（payload 的某字段，
 * 如提交人 userId）/ {@code ROLE}（按角色编码展开全部启用用户，INAPP 用用户ID、
 * EMAIL 用邮箱、SMS 用手机号）。渠道模板：填了就按填的模板发；没填按「事件 × 渠道」
 * 自动匹配模板（同一事件同一渠道只应有一份匹配模板）。</p>
 *
 * <p>限额：每个渠道行可设每日上限（0/空 = 不限），口径两种——按收件人（同一人当天
 * 同一规则同一渠道的条数）或全局（该规则该渠道当天总条数）。计数放 Redis，
 * 成功发送后才 +1，跨天按服务器时区自然归零。</p>
 *
 * <p>业务侧异步触发用 {@link #publishAsync}：通知失败绝不阻断主流程，结果只记日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyPublisher {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String QUOTA_KEY_PREFIX = "astral:notify:quota:";

    private final SysNotifyRuleService ruleService;
    private final SysNotifyRuleChannelMapper ruleChannelMapper;
    private final SysNotifyRuleRecipientMapper ruleRecipientMapper;
    private final SysMailTemplateMapper mailTemplateMapper;
    private final SysSmsTemplateMapper smsTemplateMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final MailService mailService;
    private final SmsService smsService;
    private final InappService inappService;
    private final StringRedisTemplate stringRedisTemplate;

    /** 单条投递结果 */
    public record PublishResult(Long ruleId, String ruleName, String channel,
                                String recipient, boolean ok, String error) {
    }

    /**
     * 发布事件：按全部启用规则逐条投递。
     *
     * @param eventCode          事件码（需在 NotifyEventRegistry 登记）
     * @param payload            事件上下文（事件声明的 payload 字段 → 值；可带 meta 字段
     *                           {@code noticeType} 指定站内信类型标签）
     * @param recipientsOverride 测试用：非空时替代规则收件人（对每条规则每条渠道都用这些收件人试投）
     */
    public List<PublishResult> publish(String eventCode, Map<String, String> payload, List<String> recipientsOverride) {
        List<SysNotifyRule> rules = ruleService.list(new LambdaQueryWrapper<SysNotifyRule>()
                .eq(SysNotifyRule::getEventCode, eventCode)
                .eq(SysNotifyRule::getEnabled, 1));
        List<PublishResult> results = new ArrayList<>();
        if (rules.isEmpty()) {
            log.info("[Notify] 事件 {} 无启用的订阅规则，跳过", eventCode);
            return results;
        }
        for (SysNotifyRule rule : rules) {
            List<SysNotifyRuleChannel> channels = ruleChannelMapper.selectList(
                    new LambdaQueryWrapper<SysNotifyRuleChannel>()
                            .eq(SysNotifyRuleChannel::getRuleId, rule.getId())
                            .eq(SysNotifyRuleChannel::getEnabled, 1));
            if (channels.isEmpty()) {
                log.info("[Notify] 规则[{}] 无启用渠道，跳过", rule.getRuleName());
                continue;
            }
            for (SysNotifyRuleChannel channel : channels) {
                List<SysNotifyRuleRecipient> recipients = ruleRecipientMapper.selectList(
                        new LambdaQueryWrapper<SysNotifyRuleRecipient>()
                                .eq(SysNotifyRuleRecipient::getChannelId, channel.getId()));
                List<String> resolved = resolveRecipients(rule, channel, recipients, payload, recipientsOverride);
                if (resolved.isEmpty()) {
                    results.add(new PublishResult(rule.getId(), rule.getRuleName(), channel.getChannel(),
                            "-", false, "收件人为空（FIXED 未配置、payload 缺字段或角色下无启用用户）"));
                    continue;
                }
                for (String recipient : resolved) {
                    results.add(deliver(rule, channel, recipient, payload));
                }
            }
        }
        return results;
    }

    /**
     * 业务侧异步触发：逐规则投递，结果只记日志（单规则失败不影响其余，更不影响业务主流程）。
     * 反馈插件等「通知不能挡主流程」的调用方用这个入口。
     */
    @Async
    public void publishAsync(String eventCode, Map<String, String> payload) {
        try {
            List<PublishResult> results = publish(eventCode, payload, null);
            results.stream().filter(r -> !r.ok()).forEach(r ->
                    log.warn("[Notify] 异步投递失败 规则[{}]（{} → {}）: {}",
                            r.ruleName(), r.channel(), r.recipient(), r.error()));
        } catch (Exception e) {
            log.warn("[Notify] 事件 {} 异步发布失败: {}", eventCode, e.getMessage());
        }
    }

    /** 收件人解析：FIXED 直取 / PAYLOAD_FIELD 取 payload 字段 / ROLE 按角色展开；测试覆盖列表优先 */
    private List<String> resolveRecipients(SysNotifyRule rule, SysNotifyRuleChannel channel,
                                           List<SysNotifyRuleRecipient> recipients,
                                           Map<String, String> payload, List<String> override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        if (recipients == null || recipients.isEmpty()) {
            return List.of();
        }
        List<String> resolved = new ArrayList<>();
        for (SysNotifyRuleRecipient recipient : recipients) {
            String type = recipient.getRecipientType() == null ? "" : recipient.getRecipientType().toUpperCase();
            String value = recipient.getRecipientValue();
            switch (type) {
                case "PAYLOAD_FIELD" -> {
                    String v = payload == null || value == null ? null : payload.get(value);
                    if (v != null && !v.isBlank()) {
                        resolved.add(v.trim());
                    }
                }
                case "ROLE" -> resolved.addAll(expandRole(channel, value));
                default -> {
                    if (value != null && !value.isBlank()) {
                        resolved.add(value.trim());
                    }
                }
            }
        }
        return resolved.stream().distinct().toList();
    }

    /**
     * 角色展开：按渠道把启用用户映射成收件值——INAPP 用用户ID、EMAIL 用邮箱、
     * SMS 用手机号，空联系方式跳过。取人口径：启用且未删除的用户。
     */
    private List<String> expandRole(SysNotifyRuleChannel channel, String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return List.of();
        }
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, roleCode.trim()).last("limit 1"));
        if (role == null) {
            log.warn("[Notify] 收件人角色 {} 不存在，规则跳过", roleCode);
            return List.of();
        }
        List<Long> userIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRoleId, role.getId()))
                .stream().map(UserRole::getUserId).distinct().toList();
        if (userIds.isEmpty()) {
            return List.of();
        }
        // 仅启用且未删除的账号
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .in(User::getId, userIds)
                .eq(User::getStatus, 1)
                .eq(User::getDeleted, 0));
        boolean email = NotifyChannel.EMAIL.equalsIgnoreCase(channel.getChannel());
        boolean phone = NotifyChannel.SMS.equalsIgnoreCase(channel.getChannel());
        return users.stream()
                .map(u -> email ? u.getEmail() : phone ? u.getPhone()
                        : u.getId() == null ? "" : u.getId().toString())
                .map(v -> v == null ? "" : v.trim())
                .filter(v -> !v.isEmpty())
                .toList();
    }

    private PublishResult deliver(SysNotifyRule rule, SysNotifyRuleChannel channel,
                                  String recipient, Map<String, String> payload) {
        String channelCode = channel.getChannel() == null ? "" : channel.getChannel().toUpperCase();
        if (recipient == null || recipient.isBlank()) {
            return new PublishResult(rule.getId(), rule.getRuleName(), channelCode,
                    "-", false, "收件人为空（FIXED 未配置、payload 缺字段或角色下无启用用户）");
        }
        try {
            Long templateId = resolveTemplateId(rule, channel);
            if (exceedQuota(rule, channel, recipient, channelCode)) {
                throw new IllegalStateException("超出每日限额（" + channel.getDailyLimit() + " 条/天，口径 "
                        + (channel.getDailyLimitScope() == null ? "RECIPIENT" : channel.getDailyLimitScope()) + "）");
            }
            switch (channelCode) {
                case NotifyChannel.EMAIL -> mailService.testSendTemplate(templateId, null, recipient, payload);
                case NotifyChannel.SMS -> smsService.testSendTemplate(templateId, null, recipient, payload);
                case NotifyChannel.INAPP -> {
                    long userId;
                    try {
                        userId = Long.parseLong(recipient.trim());
                    } catch (NumberFormatException nfe) {
                        throw new IllegalStateException("站内信收件人必须是用户ID（收到: " + recipient + "）");
                    }
                    inappService.sendTemplateRow(templateId, userId, payload, channel.getPlatform());
                }
                default -> {
                    return new PublishResult(rule.getId(), rule.getRuleName(), channelCode,
                            recipient, false, "未知渠道: " + channel.getChannel());
                }
            }
            increaseQuota(rule, channel, recipient, channelCode);
            return new PublishResult(rule.getId(), rule.getRuleName(), channelCode, recipient, true, null);
        } catch (Exception e) {
            log.warn("[Notify] 规则[{}]投递失败（{} → {}）: {}", rule.getRuleName(), channelCode, recipient, e.getMessage());
            return new PublishResult(rule.getId(), rule.getRuleName(), channelCode, recipient, false, e.getMessage());
        }
    }

    /**
     * 模板解析：渠道行填了 templateId 就用它（兜底校验存在且与事件/渠道一致）；
     * 没填就按「事件 × 渠道」自动匹配（邮件/站内信查 sys_mail_template.scene+channel，
     * 短信查 sys_sms_template.event_code）。
     */
    private Long resolveTemplateId(SysNotifyRule rule, SysNotifyRuleChannel channel) {
        String channelCode = channel.getChannel() == null ? "" : channel.getChannel().toUpperCase();
        if (channel.getTemplateId() != null) {
            if (NotifyChannel.SMS.equals(channelCode)) {
                SysSmsTemplate tpl = smsTemplateMapper.selectById(channel.getTemplateId());
                if (tpl == null || !rule.getEventCode().equals(tpl.getEventCode())) {
                    throw new IllegalStateException("短信模板缺失或与规则的事件不一致（templateId="
                            + channel.getTemplateId() + "）");
                }
            } else {
                SysMailTemplate tpl = mailTemplateMapper.selectById(channel.getTemplateId());
                if (tpl == null || !channelCode.equalsIgnoreCase(tpl.getChannel())
                        || (tpl.getScene() != null && !tpl.getScene().equals(rule.getEventCode()))) {
                    throw new IllegalStateException("邮件模板缺失或与规则的事件/渠道不一致（templateId="
                            + channel.getTemplateId() + "）");
                }
            }
            return channel.getTemplateId();
        }
        if (NotifyChannel.SMS.equals(channelCode)) {
            SysSmsTemplate tpl = smsTemplateMapper.selectOne(new LambdaQueryWrapper<SysSmsTemplate>()
                    .eq(SysSmsTemplate::getEventCode, rule.getEventCode()).last("limit 1"));
            if (tpl == null) {
                throw new IllegalStateException("事件 " + rule.getEventCode() + " 没有短信模板，且渠道未指定模板");
            }
            return tpl.getId();
        }
        SysMailTemplate tpl = mailTemplateMapper.selectOne(new LambdaQueryWrapper<SysMailTemplate>()
                .eq(SysMailTemplate::getScene, rule.getEventCode())
                .eq(SysMailTemplate::getChannel, channelCode).last("limit 1"));
        if (tpl == null) {
            throw new IllegalStateException("事件 " + rule.getEventCode() + " 没有 " + channelCode
                    + " 模板，且渠道未指定模板");
        }
        return tpl.getId();
    }

    private String quotaKey(SysNotifyRule rule, SysNotifyRuleChannel channel, String recipient) {
        boolean global = "GLOBAL".equalsIgnoreCase(channel.getDailyLimitScope());
        return QUOTA_KEY_PREFIX + rule.getId() + ":" + channel.getChannel().toUpperCase()
                + ":" + (global ? "_" : recipient) + ":" + LocalDate.now().format(DAY);
    }

    private boolean exceedQuota(SysNotifyRule rule, SysNotifyRuleChannel channel,
                                String recipient, String channelCode) {
        Integer limit = channel.getDailyLimit();
        if (limit == null || limit <= 0) {
            return false;
        }
        String value = stringRedisTemplate.opsForValue().get(quotaKey(rule, channel, recipient));
        int used = value == null ? 0 : Integer.parseInt(value);
        return used >= limit;
    }

    private void increaseQuota(SysNotifyRule rule, SysNotifyRuleChannel channel,
                               String recipient, String channelCode) {
        Integer limit = channel.getDailyLimit();
        if (limit == null || limit <= 0) {
            return;
        }
        String key = quotaKey(rule, channel, recipient);
        Long used = stringRedisTemplate.opsForValue().increment(key);
        if (used != null && used == 1L) {
            LocalDateTime midnight = LocalDate.now().plusDays(1).atStartOfDay();
            long ttlSeconds = java.time.Duration.between(LocalDateTime.now(), midnight).getSeconds() + 60;
            stringRedisTemplate.expire(key,
                    java.time.Duration.ofSeconds(Math.max(ttlSeconds, 60)));
        }
    }
}
