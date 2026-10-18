package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.entity.AiPromptTemplateEntity;
import com.astral.ai.entity.AiPromptVersionEntity;
import com.astral.ai.mapper.AiPromptTemplateMapper;
import com.astral.ai.mapper.AiPromptVersionMapper;
import com.astral.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词模板与版本服务。
 *
 * <p>提示词和智能体版本一样走「草稿 → 发布 → 归档」的不可变链路：智能体版本引用的是
 * <b>具体的提示词版本 ID</b>，所以改提示词不会悄悄改变历史智能体的行为。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiPromptService {

    private final AiPromptTemplateMapper templateMapper;
    private final AiPromptVersionMapper versionMapper;

    // ==================== 模板 ====================

    /** 全部模板 */
    public List<AiPromptTemplateEntity> listTemplates() {
        return templateMapper.selectList(new LambdaQueryWrapper<AiPromptTemplateEntity>()
                .orderByAsc(AiPromptTemplateEntity::getId));
    }

    /** 取模板 */
    public AiPromptTemplateEntity findTemplate(Long id) {
        return id == null ? null : templateMapper.selectById(id);
    }

    /** 取模板，不存在抛错 */
    public AiPromptTemplateEntity requireTemplate(Long id) {
        AiPromptTemplateEntity entity = findTemplate(id);
        if (entity == null) {
            throw new BusinessException("AI007", "提示词模板不存在: " + id);
        }
        return entity;
    }

    /** 按编码取模板 */
    public AiPromptTemplateEntity findTemplateByCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return templateMapper.selectOne(new LambdaQueryWrapper<AiPromptTemplateEntity>()
                .eq(AiPromptTemplateEntity::getPromptCode, code.trim())
                .last("limit 1"));
    }

    /** 新增模板 */
    @Transactional(rollbackFor = Exception.class)
    public AiPromptTemplateEntity createTemplate(String promptCode, String name, String scene,
                                                 String description, Long operatorId) {
        if (findTemplateByCode(promptCode) != null) {
            throw new BusinessException("AI007", "提示词编码已存在: " + promptCode);
        }
        AiPromptTemplateEntity entity = new AiPromptTemplateEntity();
        entity.setPromptCode(promptCode);
        entity.setName(name);
        entity.setScene(scene);
        entity.setDescription(description);
        entity.setStatus(AiConstants.AgentStatus.DRAFT);
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        templateMapper.insert(entity);
        return entity;
    }

    /** 更新模板 */
    @Transactional(rollbackFor = Exception.class)
    public AiPromptTemplateEntity updateTemplate(Long id, String name, String scene, String description,
                                                 Long operatorId) {
        AiPromptTemplateEntity entity = requireTemplate(id);
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        if (scene != null) {
            entity.setScene(scene);
        }
        if (description != null) {
            entity.setDescription(description);
        }
        entity.setUpdatedBy(operatorId);
        templateMapper.updateById(entity);
        return entity;
    }

    // ==================== 版本 ====================

    /** 某模板的全部版本（新到旧） */
    public List<AiPromptVersionEntity> listVersions(Long templateId) {
        return versionMapper.selectList(new LambdaQueryWrapper<AiPromptVersionEntity>()
                .eq(AiPromptVersionEntity::getTemplateId, templateId)
                .orderByDesc(AiPromptVersionEntity::getVersionNo));
    }

    /** 取版本（含草稿） */
    public AiPromptVersionEntity findVersion(Long id) {
        return id == null ? null : versionMapper.selectById(id);
    }

    /** 取版本，不存在抛错 */
    public AiPromptVersionEntity requireVersion(Long id) {
        AiPromptVersionEntity entity = findVersion(id);
        if (entity == null) {
            throw new BusinessException("AI007", "提示词版本不存在: " + id);
        }
        return entity;
    }

    /** 取已发布版本（智能体发布校验用） */
    public AiPromptVersionEntity requirePublished(Long id) {
        AiPromptVersionEntity entity = requireVersion(id);
        if (!AiConstants.VersionStatus.PUBLISHED.equals(entity.getStatus())) {
            throw new BusinessException("AI007", "提示词版本未发布: v" + entity.getVersionNo());
        }
        return entity;
    }

    /** 新建草稿版本 */
    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersionEntity createVersion(Long templateId, String systemPrompt, String userPromptTemplate,
                                               String variablesJson, String changelog, Long operatorId) {
        requireTemplate(templateId);
        List<AiPromptVersionEntity> versions = listVersions(templateId);
        int nextNo = versions.isEmpty() ? 1 : versions.get(0).getVersionNo() + 1;
        AiPromptVersionEntity entity = new AiPromptVersionEntity();
        entity.setTemplateId(templateId);
        entity.setVersionNo(nextNo);
        entity.setStatus(AiConstants.VersionStatus.DRAFT);
        entity.setSystemPrompt(systemPrompt);
        entity.setUserPromptTemplate(userPromptTemplate);
        entity.setVariablesJson(variablesJson);
        entity.setChangelog(changelog);
        entity.setCreatedBy(operatorId);
        versionMapper.insert(entity);
        return entity;
    }

    /** 更新草稿版本（已发布不可改） */
    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersionEntity updateVersion(Long id, String systemPrompt, String userPromptTemplate,
                                               String variablesJson, String changelog, Long operatorId) {
        AiPromptVersionEntity entity = requireVersion(id);
        if (!AiConstants.VersionStatus.DRAFT.equals(entity.getStatus())) {
            throw new BusinessException("AI007", "提示词版本 v" + entity.getVersionNo() + " 已发布，不可修改");
        }
        if (systemPrompt != null) {
            entity.setSystemPrompt(systemPrompt);
        }
        if (userPromptTemplate != null) {
            entity.setUserPromptTemplate(userPromptTemplate);
        }
        if (variablesJson != null) {
            entity.setVariablesJson(variablesJson);
        }
        if (changelog != null) {
            entity.setChangelog(changelog);
        }
        versionMapper.updateById(entity);
        return entity;
    }

    /** 发布版本：同模板下旧的已发布版本归档 */
    @Transactional(rollbackFor = Exception.class)
    public AiPromptVersionEntity publish(Long versionId, Long operatorId) {
        AiPromptVersionEntity entity = requireVersion(versionId);
        if (!AiConstants.VersionStatus.DRAFT.equals(entity.getStatus())) {
            throw new BusinessException("AI007", "只有草稿版本可以发布");
        }
        if (entity.getSystemPrompt() == null || entity.getSystemPrompt().isBlank()) {
            throw new BusinessException("AI007", "系统提示词不能为空");
        }
        List<AiPromptVersionEntity> published = versionMapper.selectList(
                new LambdaQueryWrapper<AiPromptVersionEntity>()
                        .eq(AiPromptVersionEntity::getTemplateId, entity.getTemplateId())
                        .eq(AiPromptVersionEntity::getStatus, AiConstants.VersionStatus.PUBLISHED));
        for (AiPromptVersionEntity old : published) {
            if (!old.getId().equals(entity.getId())) {
                old.setStatus(AiConstants.VersionStatus.ARCHIVED);
                versionMapper.updateById(old);
            }
        }
        entity.setStatus(AiConstants.VersionStatus.PUBLISHED);
        entity.setPublishedBy(operatorId);
        entity.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(entity);

        AiPromptTemplateEntity template = requireTemplate(entity.getTemplateId());
        template.setStatus(AiConstants.AgentStatus.PUBLISHED);
        template.setUpdatedBy(operatorId);
        templateMapper.updateById(template);
        return entity;
    }

    /** 最近一次发布的版本号（列表展示） */
    public AiPromptVersionEntity latestPublished(Long templateId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<AiPromptVersionEntity>()
                .eq(AiPromptVersionEntity::getTemplateId, templateId)
                .eq(AiPromptVersionEntity::getStatus, AiConstants.VersionStatus.PUBLISHED)
                .orderByDesc(AiPromptVersionEntity::getVersionNo)
                .last("limit 1"));
    }
}
