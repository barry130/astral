package com.astral.ai.service;

import com.astral.ai.common.AiPricing;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        AiModelEntity entity = modelMapper.selectOne(new LambdaQueryWrapper<AiModelEntity>()
                .eq(AiModelEntity::getId, id)
                .last("FOR UPDATE"));
        if (entity == null) throw new BusinessException("AI006", "模型不存在");
        if (input.getProviderId() != null && !input.getProviderId().equals(entity.getProviderId())) {
            if (modelMapper.countRouteReferences(id) > 0) throw new BusinessException("AI006", "模型仍被路由引用，不能更换提供方");
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
        entity.setSupportsStream(
                input.getSupportsStream() == null ? entity.getSupportsStream() : input.getSupportsStream());
        entity.setSupportsTools(
                input.getSupportsTools() == null ? entity.getSupportsTools() : input.getSupportsTools());
        entity.setSupportsJson(input.getSupportsJson() == null ? entity.getSupportsJson() : input.getSupportsJson());
        entity.setSupportsVision(
                input.getSupportsVision() == null ? entity.getSupportsVision() : input.getSupportsVision());
        entity.setMaxContextTokens(
                input.getMaxContextTokens() == null ? entity.getMaxContextTokens() : input.getMaxContextTokens());
        entity.setMaxOutputTokens(
                input.getMaxOutputTokens() == null ? entity.getMaxOutputTokens() : input.getMaxOutputTokens());
        entity.setInputPrice(input.getInputPrice() == null ? entity.getInputPrice() : input.getInputPrice());
        entity.setOutputPrice(input.getOutputPrice() == null ? entity.getOutputPrice() : input.getOutputPrice());
        entity.setCurrency(input.getCurrency() == null ? entity.getCurrency() : input.getCurrency());
        entity.setEnabled(input.getEnabled() == null ? entity.getEnabled() : input.getEnabled());
        entity.setRemark(input.getRemark() == null ? entity.getRemark() : input.getRemark());
        normalize(entity);
        var patch = new LambdaUpdateWrapper<AiModelEntity>().eq(AiModelEntity::getId, id);
        if (input.getProviderId() != null) patch.set(AiModelEntity::getProviderId, entity.getProviderId());
        if (input.getModelCode() != null && !input.getModelCode().isBlank())
            patch.set(AiModelEntity::getModelCode, entity.getModelCode());
        if (input.getModelName() != null && !input.getModelName().isBlank())
            patch.set(AiModelEntity::getModelName, entity.getModelName());
        if (input.getModelKind() != null) patch.set(AiModelEntity::getModelKind, entity.getModelKind());
        if (input.getSupportsStream() != null) patch.set(AiModelEntity::getSupportsStream, entity.getSupportsStream());
        if (input.getSupportsTools() != null) patch.set(AiModelEntity::getSupportsTools, entity.getSupportsTools());
        if (input.getSupportsJson() != null) patch.set(AiModelEntity::getSupportsJson, entity.getSupportsJson());
        if (input.getSupportsVision() != null) patch.set(AiModelEntity::getSupportsVision, entity.getSupportsVision());
        if (input.getMaxContextTokens() != null)
            patch.set(AiModelEntity::getMaxContextTokens, entity.getMaxContextTokens());
        if (input.getMaxOutputTokens() != null)
            patch.set(AiModelEntity::getMaxOutputTokens, entity.getMaxOutputTokens());
        if (input.getInputPrice() != null) patch.set(AiModelEntity::getInputPrice, entity.getInputPrice());
        if (input.getOutputPrice() != null) patch.set(AiModelEntity::getOutputPrice, entity.getOutputPrice());
        if (input.getCurrency() != null) patch.set(AiModelEntity::getCurrency, entity.getCurrency());
        if (input.getEnabled() != null) patch.set(AiModelEntity::getEnabled, entity.getEnabled());
        if (input.getRemark() != null) patch.set(AiModelEntity::getRemark, entity.getRemark());
        patch.set(AiModelEntity::getUpdateTime, java.time.LocalDateTime.now());
        modelMapper.update(null, patch);
        return require(id);
    }

    /** 删除模型（被路由引用时拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var model = modelMapper.selectOne(new LambdaQueryWrapper<AiModelEntity>()
                .eq(AiModelEntity::getId, id)
                .last("FOR UPDATE"));
        if (model == null) throw new BusinessException("AI006", "模型不存在");
        if (modelMapper.countRouteReferences(id) > 0) {
            throw new BusinessException("AI006", "模型仍被路由引用，请先调整路由");
        }
        modelMapper.deleteById(id);
        log.info("[AI] 已删除模型 id={}", id);
    }

    private void normalize(AiModelEntity entity) {
        entity.setCurrency(AiPricing.validate(entity.getInputPrice(), entity.getOutputPrice(), entity.getCurrency()));
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
