package com.astral.ai.service;

import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiProviderRouteEntity;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.ai.mapper.AiProviderRouteMapper;
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
 * 模型路由服务。
 *
 * <p>路由把「用例」和「具体的提供方 + 模型」解耦：智能体版本只引用 routeId，
 * 换模型时改路由即可，不必逐个改智能体版本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRouteService {

    private final AiProviderRouteMapper routeMapper;
    private final AiModelMapper modelMapper;
    private final AiProviderService providerService;

    /** 全部路由 */
    public List<AiProviderRouteEntity> list() {
        return routeMapper.selectList(
                new LambdaQueryWrapper<AiProviderRouteEntity>().orderByAsc(AiProviderRouteEntity::getId));
    }

    /** 取路由，不存在抛错 */
    public AiProviderRouteEntity require(Long id) {
        if (id == null) {
            throw new BusinessException("AI007", "未指定模型路由");
        }
        AiProviderRouteEntity entity = routeMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("AI007", "模型路由不存在: " + id);
        }
        return entity;
    }

    /** 取启用路由（校验上游提供方与模型都可用） */
    public AiProviderRouteEntity requireEnabled(Long id) {
        AiProviderRouteEntity route = require(id);
        if (!Boolean.TRUE.equals(route.getEnabled())) {
            throw new BusinessException("AI007", "模型路由已禁用: " + route.getName());
        }
        providerService.requireEnabled(route.getProviderId());
        AiModelEntity model = modelMapper.selectById(route.getModelId());
        if (model == null || !Boolean.TRUE.equals(model.getEnabled())) {
            throw new BusinessException("AI007", "模型路由关联的模型不可用: " + route.getName());
        }
        return route;
    }

    /** 新增路由 */
    @Transactional(rollbackFor = Exception.class)
    public AiProviderRouteEntity create(AiProviderRouteEntity input) {
        validate(input);
        AiProviderRouteEntity entity = new AiProviderRouteEntity();
        entity.setRouteCode(input.getRouteCode());
        entity.setName(input.getName());
        entity.setProviderId(input.getProviderId());
        entity.setModelId(input.getModelId());
        entity.setFallbackModelId(input.getFallbackModelId());
        entity.setTemperature(input.getTemperature());
        entity.setTopP(input.getTopP());
        entity.setMaxTokens(input.getMaxTokens());
        entity.setExtraParametersJson(input.getExtraParametersJson());
        entity.setEnabled(input.getEnabled() == null || input.getEnabled());
        entity.setRemark(input.getRemark());
        routeMapper.insert(entity);
        return entity;
    }

    /** 更新路由 */
    @Transactional(rollbackFor = Exception.class)
    public AiProviderRouteEntity update(Long id, AiProviderRouteEntity input) {
        AiProviderRouteEntity entity = routeMapper.selectOne(new LambdaQueryWrapper<AiProviderRouteEntity>()
                .eq(AiProviderRouteEntity::getId, id)
                .last("FOR UPDATE"));
        if (entity == null) throw new BusinessException("AI007", "模型路由不存在");
        if (input.getName() != null && !input.getName().isBlank()) {
            entity.setName(input.getName().trim());
        }
        if (input.getProviderId() != null) {
            entity.setProviderId(input.getProviderId());
        }
        if (input.getModelId() != null) {
            entity.setModelId(input.getModelId());
        }
        entity.setFallbackModelId(
                input.getFallbackModelId() == null ? entity.getFallbackModelId() : input.getFallbackModelId());
        entity.setTemperature(input.getTemperature() == null ? entity.getTemperature() : input.getTemperature());
        entity.setTopP(input.getTopP() == null ? entity.getTopP() : input.getTopP());
        entity.setMaxTokens(input.getMaxTokens() == null ? entity.getMaxTokens() : input.getMaxTokens());
        entity.setExtraParametersJson(
                input.getExtraParametersJson() == null
                        ? entity.getExtraParametersJson()
                        : input.getExtraParametersJson());
        entity.setEnabled(input.getEnabled() == null ? entity.getEnabled() : input.getEnabled());
        entity.setRemark(input.getRemark() == null ? entity.getRemark() : input.getRemark());
        validate(entity);
        var patch = new LambdaUpdateWrapper<AiProviderRouteEntity>().eq(AiProviderRouteEntity::getId, id);
        if (input.getName() != null && !input.getName().isBlank())
            patch.set(AiProviderRouteEntity::getName, entity.getName());
        if (input.getProviderId() != null) patch.set(AiProviderRouteEntity::getProviderId, entity.getProviderId());
        if (input.getModelId() != null) patch.set(AiProviderRouteEntity::getModelId, entity.getModelId());
        if (input.getFallbackModelId() != null)
            patch.set(AiProviderRouteEntity::getFallbackModelId, entity.getFallbackModelId());
        if (input.getTemperature() != null) patch.set(AiProviderRouteEntity::getTemperature, entity.getTemperature());
        if (input.getTopP() != null) patch.set(AiProviderRouteEntity::getTopP, entity.getTopP());
        if (input.getMaxTokens() != null) patch.set(AiProviderRouteEntity::getMaxTokens, entity.getMaxTokens());
        if (input.getExtraParametersJson() != null)
            patch.set(AiProviderRouteEntity::getExtraParametersJson, entity.getExtraParametersJson());
        if (input.getEnabled() != null) patch.set(AiProviderRouteEntity::getEnabled, entity.getEnabled());
        if (input.getRemark() != null) patch.set(AiProviderRouteEntity::getRemark, entity.getRemark());
        patch.set(AiProviderRouteEntity::getUpdateTime, java.time.LocalDateTime.now());
        routeMapper.update(null, patch);
        return require(id);
    }

    /** 删除路由（被智能体版本引用时拒绝） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        var route = routeMapper.selectOne(new LambdaQueryWrapper<AiProviderRouteEntity>()
                .eq(AiProviderRouteEntity::getId, id)
                .last("FOR UPDATE"));
        if (route == null) throw new BusinessException("AI007", "模型路由不存在");
        if (routeMapper.countVersionReferences(id) > 0) {
            throw new BusinessException("AI007", "路由仍被智能体版本引用，无法删除");
        }
        routeMapper.deleteById(id);
    }

    private void validate(AiProviderRouteEntity entity) {
        AiProviderEntity provider = providerService.require(entity.getProviderId());
        var ids = new java.util.TreeSet<Long>();
        if (entity.getModelId() != null) ids.add(entity.getModelId());
        if (entity.getFallbackModelId() != null) ids.add(entity.getFallbackModelId());
        var models = ids.isEmpty()
                ? List.<AiModelEntity>of()
                : modelMapper.selectList(new LambdaQueryWrapper<AiModelEntity>()
                        .in(AiModelEntity::getId, ids)
                        .orderByAsc(AiModelEntity::getId)
                        .last("FOR UPDATE"));
        AiModelEntity model = models.stream()
                .filter(item -> item.getId().equals(entity.getModelId()))
                .findFirst()
                .orElse(null);
        if (model == null) {
            throw new BusinessException("AI007", "模型不存在: " + entity.getModelId());
        }
        if (!provider.getId().equals(model.getProviderId())) {
            throw new BusinessException("AI007", "模型不属于所选提供方");
        }
        if (entity.getFallbackModelId() != null
                && models.stream()
                        .noneMatch(item -> item.getId().equals(entity.getFallbackModelId())
                                && provider.getId().equals(item.getProviderId()))) {
            throw new BusinessException("AI007", "备用模型不存在或不属于所选提供方");
        }
        if (entity.getTemperature() != null
                && (entity.getTemperature().compareTo(BigDecimal.ZERO) < 0
                        || entity.getTemperature().compareTo(new BigDecimal("2")) > 0)) {
            throw new BusinessException("AI007", "temperature 必须在 0 ~ 2 之间");
        }
        if (entity.getTopP() != null
                && (entity.getTopP().compareTo(BigDecimal.ZERO) < 0
                        || entity.getTopP().compareTo(BigDecimal.ONE) > 0)) {
            throw new BusinessException("AI007", "topP 必须在 0 ~ 1 之间");
        }
    }
}
