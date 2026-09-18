package com.astral.storage.service;

import com.astral.common.exception.BusinessException;
import com.astral.storage.entity.StorageAuditEntity;
import com.astral.storage.mapper.StorageAuditMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 存储审计服务
 * <p>只记录动作、主体、目标与结果；不记录 Token、完整签名 URL、locator 或文件内容。
 * 审计写入失败不影响主流程（记录告警日志）。</p>
 */
@Slf4j
@Service
public class StorageAuditService {

    @Resource
    private StorageAuditMapper auditMapper;

    public void record(String action, String subjectType, String subjectId,
                       String targetType, String targetId, String detail, String result) {
        try {
            StorageAuditEntity audit = new StorageAuditEntity();
            audit.setAction(action);
            audit.setSubjectType(subjectType);
            audit.setSubjectId(subjectId);
            audit.setTargetType(targetType);
            audit.setTargetId(targetId);
            audit.setDetail(detail);
            audit.setResult(result);
            auditMapper.insert(audit);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[Storage] 审计写入失败: action={}", action, e);
        }
    }

    public Page<StorageAuditEntity> page(long current, long size) {
        try {
            return auditMapper.selectPage(new Page<>(current, size),
                    new LambdaQueryWrapper<StorageAuditEntity>().orderByDesc(StorageAuditEntity::getCreateTime));
        } catch (Exception e) {
            throw new BusinessException("COMMON003");
        }
    }
}
