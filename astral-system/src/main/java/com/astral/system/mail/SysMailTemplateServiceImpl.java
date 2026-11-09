package com.astral.system.mail;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.system.notify.NotifyChannel;
import com.astral.system.notify.NotifyEventDef;
import com.astral.system.notify.NotifyEventRegistry;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class SysMailTemplateServiceImpl extends ServiceImpl<SysMailTemplateMapper, SysMailTemplate>
        implements SysMailTemplateService {
    private final MailService mailService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createTemplate(SysMailTemplate entity) {
        validateTemplate(entity, null);
        save(entity);
        evictAfterCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(Long id, SysMailTemplate entity) {
        SysMailTemplate effective = this.lambdaQuery()
                .eq(SysMailTemplate::getId, id)
                .last("FOR UPDATE")
                .one();
        if (effective == null) throw BusinessException.of("模板不存在");
        // Work on a detached effective value; persist only the submitted whitelist below.
        SysMailTemplate merged = new SysMailTemplate();
        BeanUtils.copyProperties(effective, merged);
        if (entity.getTemplateCode() != null) merged.setTemplateCode(entity.getTemplateCode());
        if (entity.getScene() != null) merged.setScene(entity.getScene());
        if (entity.getChannel() != null) merged.setChannel(entity.getChannel());
        if (entity.getSubject() != null) merged.setSubject(entity.getSubject());
        if (entity.getContent() != null) merged.setContent(entity.getContent());
        if (entity.getVariables() != null) merged.setVariables(entity.getVariables());
        validateTemplate(merged, id);
        // 白名单更新：id 由路径决定，只写模板可编辑列。
        // 不能用 updateById(客户端实体)——MP 会把实体上所有非 null 字段写进 SET
        // （mass assignment），客户端可借未知列改写库内任意字段。
        var update = lambdaUpdate().eq(SysMailTemplate::getId, id);
        if (entity.getTemplateCode() != null) {
            update.set(SysMailTemplate::getTemplateCode, entity.getTemplateCode());
        }
        if (entity.getTemplateName() != null) {
            update.set(SysMailTemplate::getTemplateName, entity.getTemplateName());
        }
        if (entity.getSubject() != null) {
            update.set(SysMailTemplate::getSubject, entity.getSubject());
        }
        if (entity.getContent() != null) {
            update.set(SysMailTemplate::getContent, entity.getContent());
        }
        update.set(SysMailTemplate::getVariables, merged.getVariables());
        if (entity.getScene() != null) {
            update.set(SysMailTemplate::getScene, entity.getScene());
        }
        if (entity.getChannel() != null) {
            update.set(SysMailTemplate::getChannel, entity.getChannel());
        }
        if (entity.getRemark() != null) {
            update.set(SysMailTemplate::getRemark, entity.getRemark());
        }
        if (!update.update()) throw BusinessException.of("模板未更新，请刷新后重试");
        evictAfterCommit();
    }

    /** 校验完整有效状态：已注册事件/模板渠道、渠道内唯一、变量属于事件 payload。
     * 事件尚未投递的渠道允许预配置；更新调用方先合并并锁定存量行。
     */
    private void validateTemplate(SysMailTemplate entity, Long id) {
        boolean codePresent =
                entity.getTemplateCode() != null && !entity.getTemplateCode().isBlank();
        boolean scenePresent = entity.getScene() != null && !entity.getScene().isBlank();
        boolean channelPresent =
                entity.getChannel() != null && !entity.getChannel().isBlank();
        if (!codePresent) {
            throw new BusinessException("MAIL015");
        }
        if (!scenePresent) {
            throw new BusinessException("MAIL016");
        }
        if (!channelPresent) {
            throw new BusinessException("MAIL020");
        }
        if (channelPresent
                && NotifyChannel.mailTemplateChannels().stream().noneMatch(c -> c.equals(entity.getChannel()))) {
            throw new BusinessException("MAIL019", entity.getChannel());
        }
        if (codePresent) {
            // 唯一性按 (编码, 渠道) 判断：合并模板把同一编码拆成 EMAIL/INAPP 两行，
            // 只按编码判重会把「另一渠道的兄弟行」误判成重复（id 非空时已按 id 排除自身）。
            Long dup = this.lambdaQuery()
                    .eq(SysMailTemplate::getTemplateCode, entity.getTemplateCode())
                    .eq(channelPresent, SysMailTemplate::getChannel, entity.getChannel())
                    .ne(id != null, SysMailTemplate::getId, id)
                    .count();
            if (dup != null && dup > 0) {
                throw new BusinessException("MAIL008", entity.getTemplateCode());
            }
        }
        if (scenePresent) {
            NotifyEventDef event = NotifyEventRegistry.find(entity.getScene())
                    .orElseThrow(() -> new BusinessException("MAIL009", entity.getScene()));
            Long bound = this.lambdaQuery()
                    .eq(SysMailTemplate::getScene, entity.getScene())
                    .eq(channelPresent, SysMailTemplate::getChannel, entity.getChannel())
                    .ne(id != null, SysMailTemplate::getId, id)
                    .count();
            if (bound != null && bound > 0) {
                throw new BusinessException("MAIL013", entity.getScene());
            }
            // 变量校验：模板里用到的变量必须是事件 payload 字段的子集（不要求覆盖全部字段，
            // 正文没用到 ${email} 的模板同样允许保存），校验后回写归一化结果。
            entity.setVariables(
                    normalizeVariables(entity.getVariables(), entity.getSubject(), entity.getContent(), event));
        }
    }

    /**
     * 校验并归一化模板变量：显式声明 ∪ 正文/主题中出现的变量，且必须是事件 payload 字段的子集。
     *
     * @return 归一化后的变量声明串（JSON 数组，无人使用时返回 null）
     */
    private String normalizeVariables(String declaredRaw, String subject, String content, NotifyEventDef event) {
        LinkedHashSet<String> used = new LinkedHashSet<>(NotifyEventRegistry.parseList(declaredRaw));
        used.addAll(NotifyEventRegistry.extractVariables(subject));
        used.addAll(NotifyEventRegistry.extractVariables(content));
        List<String> known = event.payloadFieldNames();
        List<String> unknown = used.stream().filter(v -> !known.contains(v)).toList();
        if (!unknown.isEmpty()) {
            throw new BusinessException("MAIL022", String.join(", ", unknown), String.join(", ", known));
        }
        return used.isEmpty() ? null : NotifyEventRegistry.toJsonArray(new ArrayList<>(used));
    }

    private void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    mailService.evictTemplateCache();
                }
            });
        } else {
            mailService.evictTemplateCache();
        }
    }
}
