package com.astral.system.notify;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.system.dto.MessageTemplateSaveDto;
import com.astral.system.dto.MessageTemplateVo;
import com.astral.system.mail.MailService;
import com.astral.system.mail.SysMailTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息模板（合并视图）读写。
 *
 * <p>管理端只面对「一条模板 = 一个事件绑定的编码 + 一组渠道」，本类负责把它拆成
 * 渠道行：EMAIL、INAPP 落到 {@code sys_mail_template}（同编码、同 scene，按 channel
 * 分行），SMS 落到 {@code sys_sms_template}（供应商侧映射，不本地渲染）。发送侧逻辑不变，
 * 仍按 (scene, channel) 取模板。</p>
 *
 * <p>保存语义是「对齐」而不是「整体替换」：传入的 channels 是期望状态，已有但不在
 * channels 里的渠道行被删除，不在 channels 里的内容（如只勾 EMAIL 时提交的短信字段）
 * 不会覆盖已存在的短信行。</p>
 */
@Service
@RequiredArgsConstructor
public class MessageTemplateService {

    private final SysMailTemplateService mailTemplateService;
    private final SysSmsTemplateService smsTemplateService;
    private final MailService mailService;
    private final SmsService smsService;

    /** 邮件/站内信渠道（落 sys_mail_template） */
    private static final List<String> RENDER_CHANNELS = List.of(NotifyChannel.EMAIL, NotifyChannel.INAPP);

    // ==================== 查询 ====================

    /** 合并列表：同一 (scene, code) 的渠道行聚合为一条记录 */
    public List<MessageTemplateVo> list() {
        Map<String, MessageTemplateVo> merged = new LinkedHashMap<>();
        for (SysMailTemplate row : mailTemplateService.list()) {
            MessageTemplateVo vo = merged.computeIfAbsent(key(row.getScene(), row.getTemplateCode()),
                    k -> newVo(row.getScene(), row.getTemplateCode()));
            vo.getChannels().add(row.getChannel());
            // EMAIL 行的主题/正文优先；只有 INAPP 行时用 INAPP 的内容兜底
            if (vo.getSubject() == null || NotifyChannel.EMAIL.equals(row.getChannel())) {
                vo.setSubject(row.getSubject());
                vo.setContent(row.getContent());
                vo.setVariables(row.getVariables());
                vo.setTemplateName(row.getTemplateName());
            }
            if (row.getRemark() != null && (vo.getRemark() == null || NotifyChannel.EMAIL.equals(row.getChannel()))) {
                vo.setRemark(row.getRemark());
            }
            vo.setUpdateTime(later(vo.getUpdateTime(), row.getUpdateTime()));
        }
        for (SysSmsTemplate row : smsTemplateService.list()) {
            MessageTemplateVo vo = merged.computeIfAbsent(key(row.getEventCode(), row.getTemplateCode()),
                    k -> newVo(row.getEventCode(), row.getTemplateCode()));
            vo.getChannels().add(NotifyChannel.SMS);
            if (vo.getTemplateName() == null) {
                vo.setTemplateName(row.getTemplateName());
            }
            if (vo.getRemark() == null) {
                vo.setRemark(row.getRemark());
            }
            vo.setSmsProviderTemplateCode(row.getProviderTemplateCode());
            vo.setSmsSignName(row.getSignName());
            vo.setSmsContentSample(row.getContentSample());
            vo.setSmsAuditStatus(row.getAuditStatus());
            vo.setSmsAuditRemark(row.getAuditRemark());
            vo.setUpdateTime(later(vo.getUpdateTime(), row.getUpdateTime()));
        }
        List<MessageTemplateVo> result = new ArrayList<>(merged.values());
        for (MessageTemplateVo vo : result) {
            vo.setChannels(NotifyChannel.registered().stream().filter(vo.getChannels()::contains).toList());
            NotifyEventRegistry.find(vo.getScene())
                    .ifPresent(def -> vo.setSceneName(def.name()));
        }
        result.sort((a, b) -> {
            int byScene = Objects.toString(a.getScene(), "").compareTo(Objects.toString(b.getScene(), ""));
            return byScene != 0 ? byScene
                    : Objects.toString(a.getTemplateCode(), "").compareTo(Objects.toString(b.getTemplateCode(), ""));
        });
        return result;
    }

    /** 事件下拉：模板只能绑定代码注册过的事件，且渠道受事件声明的渠道约束 */
    public List<Map<String, Object>> eventOptions() {
        List<Map<String, Object>> options = new ArrayList<>();
        for (NotifyEventDef def : NotifyEventRegistry.list()) {
            options.add(Map.of(
                    "code", def.code(),
                    "name", def.name(),
                    "description", Objects.toString(def.description(), ""),
                    "channels", def.channels(),
                    "payloadFields", def.payloadFieldNames()));
        }
        return options;
    }

    // ==================== 写入 ====================

    @Transactional
    public void save(MessageTemplateSaveDto dto) {
        String code = require(dto.getTemplateCode(), "MAIL015");
        String scene = require(dto.getScene(), "MAIL016");
        NotifyEventDef event = NotifyEventRegistry.find(scene)
                .orElseThrow(() -> new BusinessException("MAIL009", scene));
        String name = require(dto.getTemplateName(), "MAIL016");
        List<String> channels = normalizeChannels(dto.getChannels(), event);
        boolean renderChannel = channels.stream().anyMatch(RENDER_CHANNELS::contains);
        if (renderChannel) {
            requireVariables(dto.getVariables(), event);
        }
        checkCodeAvailable(code, scene, channels);

        String origCode = blankToNull(dto.getOriginalCode()) == null ? code : dto.getOriginalCode().trim();
        String origScene = blankToNull(dto.getOriginalScene()) == null ? scene : dto.getOriginalScene().trim();

        List<SysMailTemplate> existingMail = mailTemplateService.lambdaQuery()
                .eq(SysMailTemplate::getTemplateCode, origCode)
                .eq(SysMailTemplate::getScene, origScene)
                .list();
        SysSmsTemplate existingSms = smsTemplateService.lambdaQuery()
                .eq(SysSmsTemplate::getTemplateCode, origCode)
                .eq(SysSmsTemplate::getEventCode, origScene)
                .list().stream().findFirst().orElse(null);

        // 1) 删除不再需要的渠道行（取消勾选 / 改了编码或事件）
        for (SysMailTemplate row : existingMail) {
            boolean keep = channels.contains(row.getChannel())
                    && code.equals(origCode) && scene.equals(origScene);
            if (!keep) {
                mailTemplateService.removeById(row.getId());
            }
        }
        if (existingSms != null
                && (!channels.contains(NotifyChannel.SMS) || !code.equals(origCode) || !scene.equals(origScene))) {
            smsTemplateService.removeById(existingSms.getId());
            existingSms = null;
        }

        // 2) 写入勾选渠道
        for (String channel : channels) {
            if (RENDER_CHANNELS.contains(channel)) {
                SysMailTemplate row = existingMail.stream()
                        .filter(r -> channel.equals(r.getChannel()))
                        .findFirst().orElseGet(SysMailTemplate::new);
                row.setTemplateCode(code);
                row.setTemplateName(name);
                row.setScene(scene);
                row.setChannel(channel);
                row.setSubject(dto.getSubject());
                row.setContent(dto.getContent());
                row.setVariables(dto.getVariables());
                row.setRemark(dto.getRemark());
                if (row.getId() == null) {
                    mailTemplateService.save(row);
                } else {
                    mailTemplateService.updateById(row);
                }
            }
        }
        if (channels.contains(NotifyChannel.SMS)) {
            SysSmsTemplate row = existingSms == null ? new SysSmsTemplate() : existingSms;
            row.setTemplateCode(code);
            row.setTemplateName(name);
            row.setEventCode(scene);
            row.setProviderTemplateCode(dto.getSmsProviderTemplateCode());
            row.setSignName(dto.getSmsSignName());
            row.setContentSample(dto.getSmsContentSample());
            row.setAuditStatus(dto.getSmsAuditStatus() == null ? 0 : dto.getSmsAuditStatus());
            row.setAuditRemark(dto.getSmsAuditRemark());
            row.setRemark(dto.getRemark());
            if (row.getId() == null) {
                smsTemplateService.save(row);
            } else {
                smsTemplateService.updateById(row);
            }
        }

        mailService.evictTemplateCache();
        smsService.evictTemplateCache();
    }

    @Transactional
    public void delete(String templateCode, String scene) {
        String code = require(templateCode, "MAIL015");
        String sc = require(scene, "MAIL016");
        mailTemplateService.lambdaUpdate()
                .eq(SysMailTemplate::getTemplateCode, code)
                .eq(SysMailTemplate::getScene, sc)
                .remove();
        smsTemplateService.lambdaUpdate()
                .eq(SysSmsTemplate::getTemplateCode, code)
                .eq(SysSmsTemplate::getEventCode, sc)
                .remove();
        mailService.evictTemplateCache();
        smsService.evictTemplateCache();
    }

    /** 渲染预览（只做 ${var} 替换，不引入模板引擎） */
    public String preview(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        String rendered = template;
        if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                rendered = rendered.replace("${" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
            }
        }
        return rendered;
    }

    // ==================== 校验 ====================

    private List<String> normalizeChannels(List<String> raw, NotifyEventDef event) {
        List<String> channels = raw == null ? List.of() : raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (channels.isEmpty()) {
            throw new BusinessException("MAIL020");
        }
        for (String channel : channels) {
            if (!NotifyChannel.registered().contains(channel)) {
                throw new BusinessException("MAIL019", channel);
            }
        }
        // 不再强制「渠道必须是事件声明的渠道」：模板允许把事件未声明的渠道一起配好
        // （例如先把站内信/短信的文案备好，等事件真正发往该渠道时即可生效）。
        // 事件声明渠道只在前端提示，投递时仍按 (scene, channel) 精确匹配，未声明的渠道不会被用到。
        return channels;
    }

    private void requireVariables(String variables, NotifyEventDef event) {
        List<String> declared = NotifyEventRegistry.parseList(variables);
        List<String> missing = event.payloadFieldNames().stream()
                .filter(field -> !declared.contains(field))
                .toList();
        if (!missing.isEmpty()) {
            throw new BusinessException("MAIL010", String.join(", ", missing));
        }
    }

    /**
     * 编码/事件冲突校验： (code, channel) 全表唯一（改了编码要能被旧行腾位），
     * (scene, channel) 唯一（一个事件的一个渠道只允许一条模板）。
     */
    private void checkCodeAvailable(String code, String scene, List<String> channels) {
        for (String channel : channels) {
            if (RENDER_CHANNELS.contains(channel)) {
                Long dup = mailTemplateService.lambdaQuery()
                        .eq(SysMailTemplate::getTemplateCode, code)
                        .eq(SysMailTemplate::getChannel, channel)
                        .ne(SysMailTemplate::getScene, scene)
                        .count();
                if (dup != null && dup > 0) {
                    throw new BusinessException("MAIL008", code);
                }
                Long bound = mailTemplateService.lambdaQuery()
                        .eq(SysMailTemplate::getScene, scene)
                        .eq(SysMailTemplate::getChannel, channel)
                        .ne(SysMailTemplate::getTemplateCode, code)
                        .count();
                if (bound != null && bound > 0) {
                    throw new BusinessException("MAIL013", scene);
                }
            }
        }
        if (channels.contains(NotifyChannel.SMS)) {
            Long dup = smsTemplateService.lambdaQuery()
                    .eq(SysSmsTemplate::getTemplateCode, code)
                    .ne(SysSmsTemplate::getEventCode, scene)
                    .count();
            if (dup != null && dup > 0) {
                throw new BusinessException("MAIL008", code);
            }
            Long bound = smsTemplateService.lambdaQuery()
                    .eq(SysSmsTemplate::getEventCode, scene)
                    .ne(SysSmsTemplate::getTemplateCode, code)
                    .count();
            if (bound != null && bound > 0) {
                throw new BusinessException("MAIL013", scene);
            }
        }
    }

    private MessageTemplateVo newVo(String scene, String code) {
        MessageTemplateVo vo = new MessageTemplateVo();
        vo.setScene(scene);
        vo.setTemplateCode(code);
        vo.setChannels(new ArrayList<>());
        return vo;
    }

    private static String key(String scene, String code) {
        return Objects.toString(scene, "") + '\u0000' + Objects.toString(code, "");
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.isAfter(a) ? b : a;
    }

    private static String require(String value, String errorCode) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(errorCode);
        }
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
