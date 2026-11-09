package com.astral.system.notify;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class SysSmsTemplateServiceImpl extends ServiceImpl<SysSmsTemplateMapper, SysSmsTemplate>
        implements SysSmsTemplateService {
    private final SmsService smsService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void createTemplate(SysSmsTemplate entity) {
        validateTemplate(entity, null);
        if (!save(entity)) throw BusinessException.of("短信模板保存失败");
        evictAfterCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(Long id, SysSmsTemplate entity) {
        SysSmsTemplate existing =
                lambdaQuery().eq(SysSmsTemplate::getId, id).last("FOR UPDATE").one();
        if (existing == null) throw new BusinessException("SMS002", id);
        SysSmsTemplate effective = new SysSmsTemplate();
        BeanUtils.copyProperties(existing, effective);
        if (entity.getTemplateCode() != null) effective.setTemplateCode(entity.getTemplateCode());
        if (entity.getTemplateName() != null) effective.setTemplateName(entity.getTemplateName());
        if (entity.getEventCode() != null) effective.setEventCode(entity.getEventCode());
        if (entity.getProviderTemplateCode() != null)
            effective.setProviderTemplateCode(entity.getProviderTemplateCode());
        if (entity.getAuditStatus() != null) effective.setAuditStatus(entity.getAuditStatus());
        validateTemplate(effective, id);

        var update = lambdaUpdate().eq(SysSmsTemplate::getId, id);
        boolean changed = false;
        if (entity.getTemplateCode() != null) {
            update.set(SysSmsTemplate::getTemplateCode, entity.getTemplateCode());
            changed = true;
        }
        if (entity.getTemplateName() != null) {
            update.set(SysSmsTemplate::getTemplateName, entity.getTemplateName());
            changed = true;
        }
        if (entity.getEventCode() != null) {
            update.set(SysSmsTemplate::getEventCode, entity.getEventCode());
            changed = true;
        }
        if (entity.getProviderTemplateCode() != null) {
            update.set(SysSmsTemplate::getProviderTemplateCode, entity.getProviderTemplateCode());
            changed = true;
        }
        if (entity.getSignName() != null) {
            update.set(SysSmsTemplate::getSignName, entity.getSignName());
            changed = true;
        }
        if (entity.getContentSample() != null) {
            update.set(SysSmsTemplate::getContentSample, entity.getContentSample());
            changed = true;
        }
        if (entity.getAuditStatus() != null) {
            update.set(SysSmsTemplate::getAuditStatus, entity.getAuditStatus());
            changed = true;
        }
        if (entity.getAuditRemark() != null) {
            update.set(SysSmsTemplate::getAuditRemark, entity.getAuditRemark());
            changed = true;
        }
        if (entity.getRemark() != null) {
            update.set(SysSmsTemplate::getRemark, entity.getRemark());
            changed = true;
        }
        if (!changed) return;
        update.set(SysSmsTemplate::getUpdateTime, LocalDateTime.now());
        if (!update.update()) throw BusinessException.of("短信模板未更新，请刷新后重试");
        evictAfterCommit();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long id) {
        removeById(id);
        evictAfterCommit();
    }

    /** Validate the complete effective mapping; null fields in a partial request never waive required values. */
    private void validateTemplate(SysSmsTemplate entity, Long id) {
        if (entity.getTemplateCode() == null || entity.getTemplateCode().isBlank())
            throw new BusinessException("SMS013");
        if (entity.getTemplateName() == null
                || entity.getTemplateName().isBlank()
                || entity.getEventCode() == null
                || entity.getEventCode().isBlank()) throw new BusinessException("SMS014");
        if (entity.getProviderTemplateCode() == null
                || entity.getProviderTemplateCode().isBlank()) throw new BusinessException("SMS015");
        if (entity.getAuditStatus() != null && (entity.getAuditStatus() < 0 || entity.getAuditStatus() > 3))
            throw new BusinessException("SMS016");
        Long duplicate = lambdaQuery()
                .eq(SysSmsTemplate::getTemplateCode, entity.getTemplateCode())
                .ne(id != null, SysSmsTemplate::getId, id)
                .count();
        if (duplicate != null && duplicate > 0) throw new BusinessException("SMS007", entity.getTemplateCode());
        NotifyEventDef event = NotifyEventRegistry.find(entity.getEventCode())
                .orElseThrow(() -> new BusinessException("MAIL009", entity.getEventCode()));
        if (event.channels().stream().noneMatch(NotifyChannel.SMS::equals))
            throw new BusinessException("MAIL021", entity.getEventCode(), NotifyChannel.SMS);
        Long bound = lambdaQuery()
                .eq(SysSmsTemplate::getEventCode, entity.getEventCode())
                .ne(id != null, SysSmsTemplate::getId, id)
                .count();
        if (bound != null && bound > 0) throw new BusinessException("SMS008", entity.getEventCode());
    }

    private void evictAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    smsService.evictTemplateCache();
                }
            });
        } else {
            smsService.evictTemplateCache();
        }
    }
}
