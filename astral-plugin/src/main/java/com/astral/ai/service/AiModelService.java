package com.astral.ai.service;

import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 模型配置服务。
 *
 * <p>模型是「提供方 + 上游模型编码」的组合；价格用于费用预估，
 * 上下文与输出上限会与全局硬上限取小值，防止后台把上限配到危险区间。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiModelService {

    private final AiModelMapper modelMapper;
    private final AiProviderService providerService;

    /** 全部模型 */
    public List<AiModelEntity> list(Long providerId) {
        LambdaQueryWrapper<AiModelEntity> wrapper = new LambdaQueryWrapper<>();
        if (providerId != null) {
            wrapper.eq(AiModelEntity::getProviderId, providerId);
        }
        wrapper.orderByAsc(AiModelEntity::getProviderId).orderByAsc(AiModelEntity::getId);
        return modelMapper.selectList(wrapper);
    }

    /** 取模型，不存在抛错 */
    public AiModelEntity require(Long id) {
        if (id == null) {
            throw new BusinessException("AI006", "未指定模型");
        }
        AiModelEntity entity = modelMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("AI006", "模型不存在: " + id);
        }
        return entity;
    }

    /** 取启用模型 */
    public AiModelEntity requireEnabled(Long id) {
        AiModelEntity entity = require(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw new BusinessException("AI006", "模型已禁用: " + entity.getModelName());
        }
        return entity;
    }

    /** 新增模型 */
    @Transactional(rollbackFor = Exception.class)
    public AiModelEntity create(AiModelEntity input) {
        providerService.require(input.getProviderId());
        Long exists = modelMapper.selectCount(new LambdaQueryWrapper<AiModelEntity>()
                .eq(AiModelEntity::getProviderId, input.getProviderId())
                .eq(AiModelEntity::getModelCode, input.getModelCode()));
        if (exists != null && exists > 0) {
            throw new BusinessException("AI006", "同一提供方下模型编码重复: " + input.getModelCode());
        }
        normalize(input);
        modelMapper.insert(input);
        return input;
    }

    /** 更新模型 */
    @Transactional(rollbackFor = Exception.class)
    public AiModelEntity update(Long id, AiModelEntity input) {
        AiModelEntity entity = require(id);
        if (input.getProviderId() != null && !input.getProviderId().equals(entity.getProviderId())) {
            providerService.require(input.getProviderId());
            entity.setProviderId(input.getProviderId());
        }
        if (input.getModelCode() != null && !input.getModelCode().isBlank()) {
            entity.setModelCode(input.getModelCode().trim());
        }
        if (input.getModelName() != null && !input.getModelName().isBlank()) {
            entity.setModelName(input.getModelName().trim());
        }
        entity.setModelKind(input.getModelKind() == null ? entity.getModelKind() : input.getModelKind());
        entity.setSupportsStream(input.getSupportsStream() == null ? entity.getSupportsStream() : input.getSupportsStream());
        entity.setSupportsTools(input.getSupportsTools() == null ? entity.getSupportsTools() : input.getSupportsTools());
        entity.setSupportsJson(input.getSupportsJson() == null ? entity.getSupportsJson() : input.getSupportsJson());
        entity.setSupportsVision(input.getSupportsVision() == null ? entity.getSupportsVision() : input.getSupportsVision());
        entity.setMaxContextTokens(input.getMaxContextTokens() == null ? entity.getMaxContextTokens() : input.getMaxContextTokens());
        entity.setMaxOutputTokens(input.getMaxOutputTokens() == null ? entity.getMaxOutputTokens() : input.getMaxOutputTokens());
        entity.setInputPrice(input.getInputPrice() == null ? entity.getInputPrice() : input.getInputPrice());
        entity.setOutputPrice(input.getOutputPrice() == null ? entity.getOutputPrice() : input.getOutputPrice());
        entity.setCurrency(input.getCurrency() == null ? entity.getCurrency() : input.getCurrency());
        entity.setEnabled(input.getEnabled() == null ? entity.getEnabled() : input.getEnabled());
        entity.setRemark(input.getRemark() == null ? entity.getRemark() : input.getRemark());
        normalize(entity);
        modelMapper.updateById(entity);
        return entity;
    }

    /** 删除模型（被路由引用时拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        require(id);
        Long used = modelMapper.selectCount(null);
        modelMapper.deleteById(id);
        if (used != null) {
            log.info("[AI] 已删除模型 id={}", id);
        }
    }

    private void normalize(AiModelEntity entity) {
        if (entity.getModelKind() == null || entity.getModelKind().isBlank()) {
            entity.setModelKind("CHAT");
        }
        if (entity.getSupportsStream() == null) {
            entity.setSupportsStream(true);
        }
        if (entity.getSupportsTools() == null) {
            entity.setSupportsTools(false);
        }
        if (entity.getSupportsJson() == null) {
            entity.setSupportsJson(false);
        }
        if (entity.getSupportsVision() == null) {
            entity.setSupportsVision(false);
        }
        if (entity.getEnabled() == null) {
            entity.setEnabled(true);
        }
        if (entity.getCurrency() == null || entity.getCurrency().isBlank()) {
            entity.setCurrency("CNY");
        }
        if (entity.getInputPrice() == null) {
            entity.setInputPrice(BigDecimal.ZERO);
        }
        if (entity.getOutputPrice() == null) {
            entity.setOutputPrice(BigDecimal.ZERO);
        }
    }
}
