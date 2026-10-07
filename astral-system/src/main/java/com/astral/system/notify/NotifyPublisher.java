package com.astral.system.notify;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.astral.dao.entity.Role;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.mail.MailService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 事件发布器（阶段④）：把「事件 → 渠道投递」从调用方代码里解耦出来。
 *
 * <p>调用方只管在业务节点 {@code publish(eventCode, payload)}；
 * 发给谁、走什么渠道、用什么模板全由启用的订阅规则决定（规则模板与事件/渠道的
 * 一致性在规则写路径已校验，这里只兜底防配置漂移）。逐规则隔离：单规则失败
 * 不影响其余，结果逐条回报给调用方（管理端发布测试直接展示）。</p>
 *
 * <p>收件人三种来源：FIXED（规则里写死）/ PAYLOAD_FIELD（payload 的某字段，
 * 如提交人 userId）/ ROLE（按角色编码展开全部启用用户，INAPP 用用户ID、
 * EMAIL 用邮箱、SMS 用手机号）。发送路径复用各渠道的「无插件授权」直发口：
 * EMAIL/SMS 走模板试发（渲染 + 账户/供应商池 + 日志全在），INAPP 按模板行渲染落
 * 统一通知表 sys_notice（平台定向取规则的 platform）。</p>
 *
 * <p>业务侧异步触发用 {@link #publishAsync}：通知失败绝不阻断主流程，
 * 结果只记日志（反馈插件等调用方即此用法）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyPublisher {

    private final SysNotifyRuleService ruleService;
    private final SysMailTemplateMapper mailTemplateMapper;
    private final SysSmsTemplateMapper smsTemplateMapper;
    private final RoleMapper roleMapper;
    private final UserRoleMapper userRoleMapper;
    private final UserMapper userMapper;
    private final MailService mailService;
    private final SmsService smsService;
    private final InappService inappService;

    /** 单条投递结果 */
    public record PublishResult(Long ruleId, String ruleName, String channel,
                                String recipient, boolean ok, String error) {
    }

    /**
     * 发布事件：按全部启用规则逐条投递。
     *
     * @param eventCode   事件码（需在 NotifyEventRegistry 登记）
     * @param payload     事件上下文（事件声明的 payload 字段 → 值；可带 meta 字段
     *                    {@code noticeType} 指定站内信类型标签）
     * @param recipientsOverride 测试用：非空时替代规则收件人（对每条规则都用这些收件人试投）
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
            for (String recipient : resolveRecipients(rule, payload, recipientsOverride)) {
                results.add(deliver(rule, recipient, payload));
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
    private List<String> resolveRecipients(SysNotifyRule rule, Map<String, String> payload, List<String> override) {
        if (override != null && !override.isEmpty()) {
            return override;
        }
        String type = rule.getRecipientType() == null ? "" : rule.getRecipientType().toUpperCase();
        switch (type) {
            case "PAYLOAD_FIELD": {
                String value = payload == null ? null : payload.get(rule.getRecipientValue());
                return value == null || value.isBlank() ? List.of() : List.of(value);
            }
            case "ROLE":
                return expandRole(rule, rule.getRecipientValue());
            default: {
                String value = rule.getRecipientValue();
                return value == null || value.isBlank() ? List.of() : List.of(value);
            }
        }
    }

    /**
     * 角色展开：按渠道把启用用户映射成收件值——INAPP 用用户ID、EMAIL 用邮箱、
     * SMS 用手机号，空联系方式跳过。与 feedback 插件 FeedbackNoticeService.adminUserIds
     * 的取人口径一致（启用且未删除）。
     */
    private List<String> expandRole(SysNotifyRule rule, String roleCode) {
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
        boolean email = "EMAIL".equalsIgnoreCase(rule.getChannel());
        boolean phone = "SMS".equalsIgnoreCase(rule.getChannel());
        return users.stream()
                .map(u -> email ? u.getEmail() : phone ? u.getPhone()
                        : u.getId() == null ? "" : u.getId().toString())
                .map(v -> v == null ? "" : v.trim())
                .filter(v -> !v.isEmpty())
                .toList();
    }

    private PublishResult deliver(SysNotifyRule rule, String recipient, Map<String, String> payload) {
        if (recipient == null || recipient.isBlank()) {
            return new PublishResult(rule.getId(), rule.getRuleName(), rule.getChannel(),
                    "-", false, "收件人为空（FIXED 未配置、payload 缺字段或角色下无启用用户）");
        }
        try {
            switch (rule.getChannel() == null ? "" : rule.getChannel().toUpperCase()) {
                case NotifyChannel.EMAIL -> {
                    requireMailTemplate(rule);
                    mailService.testSendTemplate(rule.getTemplateId(), null, recipient, payload);
                }
                case NotifyChannel.SMS -> {
                    requireSmsTemplate(rule);
                    smsService.testSendTemplate(rule.getTemplateId(), null, recipient, payload);
                }
                case NotifyChannel.INAPP -> {
                    long userId;
                    try {
                        userId = Long.parseLong(recipient.trim());
                    } catch (NumberFormatException nfe) {
                        throw new IllegalStateException("站内信收件人必须是用户ID（收到: " + recipient + "）");
                    }
                    inappService.sendTemplateRow(rule.getTemplateId(), userId, payload, rule.getPlatform());
                }
                default -> {
                    return new PublishResult(rule.getId(), rule.getRuleName(), rule.getChannel(),
                            recipient, false, "未知渠道: " + rule.getChannel());
                }
            }
            return new PublishResult(rule.getId(), rule.getRuleName(), rule.getChannel(), recipient, true, null);
        } catch (Exception e) {
            log.warn("[Notify] 规则[{}]投递失败（{} → {}）: {}", rule.getRuleName(), rule.getChannel(), recipient, e.getMessage());
            return new PublishResult(rule.getId(), rule.getRuleName(), rule.getChannel(), recipient, false, e.getMessage());
        }
    }

    /** 防配置漂移：模板行被删/改渠道后规则仍引用旧 id，投递前按行兜底校验 */
    private void requireMailTemplate(SysNotifyRule rule) {
        SysMailTemplate tpl = mailTemplateMapper.selectById(rule.getTemplateId());
        if (tpl == null || !rule.getChannel().equalsIgnoreCase(tpl.getChannel())
                || (tpl.getScene() != null && !tpl.getScene().equals(rule.getEventCode()))) {
            throw new IllegalStateException("邮件模板缺失或与规则的事件/渠道不一致（templateId=" + rule.getTemplateId() + "）");
        }
    }

    private void requireSmsTemplate(SysNotifyRule rule) {
        SysSmsTemplate tpl = smsTemplateMapper.selectById(rule.getTemplateId());
        if (tpl == null || !tpl.getEventCode().equals(rule.getEventCode())) {
            throw new IllegalStateException("短信模板缺失或与规则的事件不一致（templateId=" + rule.getTemplateId() + "）");
        }
    }
}
