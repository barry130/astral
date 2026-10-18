package com.astral.ai.controller;

import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiProviderRouteEntity;
import com.astral.ai.service.AiModelService;
import com.astral.ai.service.AiProviderService;
import com.astral.ai.service.AiRouteService;
import com.astral.ai.service.AiToolService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.log.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 后台：提供方、模型与路由配置。
 *
 * <p>密钥永不出现在任何响应里，只回报 {@code credentialSource}（ENCRYPTED / SECRET_REF / NONE）。
 * 编辑时 {@code apiKey} 传空表示「保持不变」，因此前端可以安全地把表单回填成空。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:config:view", name = "AI 配置查看", description = "AI 提供方/模型/路由查看")
@RequiredArgsConstructor
public class AiAdminProviderController {

    private final AiProviderService providerService;
    private final AiModelService modelService;
    private final AiRouteService routeService;
    private final AiToolService toolService;

    private static Long operatorId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            return null;
        }
        try {
            return Long.valueOf(userId.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ==================== 提供方 ====================

    @GetMapping("/providers")
    @RequiresPermission(value = "admin:ai:provider:view", name = "AI 提供方查看", description = "查看 AI 提供方")
    public Result<List<AiAdminDtos.ProviderVo>> providers() {
        List<AiAdminDtos.ProviderVo> list = new ArrayList<>();
        for (AiProviderEntity entity : providerService.list()) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @PostMapping("/providers")
    @RequiresPermission(value = "admin:ai:provider:edit", name = "AI 提供方编辑", description = "新增提供方")
    @OperateLog("新增 AI 提供方")
    public Result<AiAdminDtos.ProviderVo> createProvider(@Valid @RequestBody AiAdminDtos.ProviderSaveReq req,
                                                        HttpServletRequest request) {
        AiProviderEntity entity = providerService.create(req.providerCode(), req.name(), req.providerType(),
                req.baseUrl(), req.apiKey(), req.secretRef(), req.enabled(), req.connectTimeoutMs(),
                req.readTimeoutMs(), req.maxRetries(), req.weight(), req.remark(), operatorId(request));
        return Result.success("创建成功", toVo(entity));
    }

    @PutMapping("/providers/{id}")
    @RequiresPermission(value = "admin:ai:provider:edit", name = "AI 提供方编辑", description = "修改提供方")
    @OperateLog("修改 AI 提供方")
    public Result<AiAdminDtos.ProviderVo> updateProvider(@PathVariable Long id,
                                                        @Valid @RequestBody AiAdminDtos.ProviderSaveReq req,
                                                        HttpServletRequest request) {
        AiProviderEntity entity = providerService.update(id, req.name(), req.providerType(), req.baseUrl(),
                req.apiKey(), req.secretRef(), req.enabled(), req.connectTimeoutMs(), req.readTimeoutMs(),
                req.maxRetries(), req.weight(), req.remark(), operatorId(request));
        return Result.success("保存成功", toVo(entity));
    }

    @DeleteMapping("/providers/{id}")
    @RequiresPermission(value = "admin:ai:provider:edit", name = "AI 提供方编辑", description = "删除提供方")
    @OperateLog("删除 AI 提供方")
    public Result<Void> deleteProvider(@PathVariable Long id) {
        providerService.delete(id);
        return Result.success("已删除", null);
    }

    @PostMapping("/providers/{id}/test")
    @RequiresPermission(value = "admin:ai:provider:edit", name = "AI 提供方编辑", description = "测试提供方连通性")
    public Result<AiAdminDtos.ProviderTestVo> testProvider(@PathVariable Long id,
                                                          @RequestParam(required = false) String modelCode) {
        AiProviderEntity provider = providerService.require(id);
        com.astral.ai.provider.AiProviderDtos.HealthResult result = providerService.test(id, modelCode);
        AiProviderEntity refreshed = providerService.require(provider.getId());
        return Result.success(new AiAdminDtos.ProviderTestVo(result.healthy(), result.message(),
                result.latencyMs(), refreshed.getHealthStatus()));
    }

    // ==================== 模型 ====================

    @GetMapping("/models")
    @RequiresPermission(value = "admin:ai:model:view", name = "AI 模型查看", description = "查看 AI 模型")
    public Result<List<AiAdminDtos.ModelVo>> models(@RequestParam(required = false) Long providerId) {
        List<AiAdminDtos.ModelVo> list = new ArrayList<>();
        for (AiModelEntity entity : modelService.list(providerId)) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @PostMapping("/models")
    @RequiresPermission(value = "admin:ai:model:edit", name = "AI 模型编辑", description = "新增模型")
    @OperateLog("新增 AI 模型")
    public Result<AiAdminDtos.ModelVo> createModel(@Valid @RequestBody AiAdminDtos.ModelSaveReq req) {
        AiModelEntity entity = modelService.create(fromReq(req));
        return Result.success("创建成功", toVo(entity));
    }

    @PutMapping("/models/{id}")
    @RequiresPermission(value = "admin:ai:model:edit", name = "AI 模型编辑", description = "修改模型")
    @OperateLog("修改 AI 模型")
    public Result<AiAdminDtos.ModelVo> updateModel(@PathVariable Long id,
                                                  @Valid @RequestBody AiAdminDtos.ModelSaveReq req) {
        AiModelEntity entity = modelService.update(id, fromReq(req));
        return Result.success("保存成功", toVo(entity));
    }

    @DeleteMapping("/models/{id}")
    @RequiresPermission(value = "admin:ai:model:edit", name = "AI 模型编辑", description = "删除模型")
    @OperateLog("删除 AI 模型")
    public Result<Void> deleteModel(@PathVariable Long id) {
        modelService.delete(id);
        return Result.success("已删除", null);
    }

    // ==================== 路由 ====================

    @GetMapping("/routes")
    @RequiresPermission(value = "admin:ai:route:view", name = "AI 路由查看", description = "查看模型路由")
    public Result<List<AiAdminDtos.RouteVo>> routes() {
        List<AiAdminDtos.RouteVo> list = new ArrayList<>();
        for (AiProviderRouteEntity entity : routeService.list()) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @PostMapping("/routes")
    @RequiresPermission(value = "admin:ai:route:edit", name = "AI 路由编辑", description = "新增路由")
    @OperateLog("新增 AI 路由")
    public Result<AiAdminDtos.RouteVo> createRoute(@Valid @RequestBody AiAdminDtos.RouteSaveReq req) {
        AiProviderRouteEntity entity = routeService.create(fromReq(req));
        return Result.success("创建成功", toVo(entity));
    }

    @PutMapping("/routes/{id}")
    @RequiresPermission(value = "admin:ai:route:edit", name = "AI 路由编辑", description = "修改路由")
    @OperateLog("修改 AI 路由")
    public Result<AiAdminDtos.RouteVo> updateRoute(@PathVariable Long id,
                                                  @Valid @RequestBody AiAdminDtos.RouteSaveReq req) {
        AiProviderRouteEntity entity = routeService.update(id, fromReq(req));
        return Result.success("保存成功", toVo(entity));
    }

    @DeleteMapping("/routes/{id}")
    @RequiresPermission(value = "admin:ai:route:edit", name = "AI 路由编辑", description = "删除路由")
    @OperateLog("删除 AI 路由")
    public Result<Void> deleteRoute(@PathVariable Long id) {
        routeService.delete(id);
        return Result.success("已删除", null);
    }

    // ==================== 转换 ====================

    private AiAdminDtos.ProviderVo toVo(AiProviderEntity entity) {
        long modelCount = modelService.list(entity.getId()).size();
        return new AiAdminDtos.ProviderVo(entity.getId(), entity.getProviderCode(), entity.getName(),
                entity.getProviderType(), entity.getBaseUrl(), entity.getEnabled(), entity.getSecretRef(),
                providerService.credentialSource(entity), entity.getConnectTimeoutMs(), entity.getReadTimeoutMs(),
                entity.getMaxRetries(), entity.getWeight(), entity.getHealthStatus(), entity.getLastHealthTime(),
                entity.getLastHealthMessage(), entity.getRemark(), (int) modelCount,
                entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiAdminDtos.ModelVo toVo(AiModelEntity entity) {
        String providerName = null;
        try {
            providerName = providerService.require(entity.getProviderId()).getName();
        } catch (RuntimeException ignored) {
            providerName = null;
        }
        return new AiAdminDtos.ModelVo(entity.getId(), entity.getProviderId(), providerName,
                entity.getModelCode(), entity.getModelName(), entity.getModelKind(), entity.getSupportsStream(),
                entity.getSupportsTools(), entity.getSupportsJson(), entity.getSupportsVision(),
                entity.getMaxContextTokens(), entity.getMaxOutputTokens(), entity.getInputPrice(),
                entity.getOutputPrice(), entity.getCurrency(), entity.getEnabled(), entity.getRemark(),
                entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiAdminDtos.RouteVo toVo(AiProviderRouteEntity entity) {
        String providerName = null;
        String modelCode = null;
        String fallbackCode = null;
        try {
            providerName = providerService.require(entity.getProviderId()).getName();
        } catch (RuntimeException ignored) {
            providerName = null;
        }
        try {
            modelCode = modelService.require(entity.getModelId()).getModelCode();
        } catch (RuntimeException ignored) {
            modelCode = null;
        }
        if (entity.getFallbackModelId() != null) {
            try {
                fallbackCode = modelService.require(entity.getFallbackModelId()).getModelCode();
            } catch (RuntimeException ignored) {
                fallbackCode = null;
            }
        }
        return new AiAdminDtos.RouteVo(entity.getId(), entity.getRouteCode(), entity.getName(),
                entity.getProviderId(), providerName, entity.getModelId(), modelCode,
                entity.getFallbackModelId(), fallbackCode, entity.getTemperature(), entity.getTopP(),
                entity.getMaxTokens(), entity.getExtraParametersJson(), entity.getEnabled(), entity.getRemark(),
                entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiModelEntity fromReq(AiAdminDtos.ModelSaveReq req) {
        AiModelEntity entity = new AiModelEntity();
        entity.setProviderId(req.providerId());
        entity.setModelCode(req.modelCode());
        entity.setModelName(req.modelName());
        entity.setModelKind(req.modelKind());
        entity.setSupportsStream(req.supportsStream());
        entity.setSupportsTools(req.supportsTools());
        entity.setSupportsJson(req.supportsJson());
        entity.setSupportsVision(req.supportsVision());
        entity.setMaxContextTokens(req.maxContextTokens());
        entity.setMaxOutputTokens(req.maxOutputTokens());
        entity.setInputPrice(req.inputPrice());
        entity.setOutputPrice(req.outputPrice());
        entity.setCurrency(req.currency());
        entity.setEnabled(req.enabled());
        entity.setRemark(req.remark());
        return entity;
    }

    private AiProviderRouteEntity fromReq(AiAdminDtos.RouteSaveReq req) {
        AiProviderRouteEntity entity = new AiProviderRouteEntity();
        entity.setRouteCode(req.routeCode());
        entity.setName(req.name());
        entity.setProviderId(req.providerId());
        entity.setModelId(req.modelId());
        entity.setFallbackModelId(req.fallbackModelId());
        entity.setTemperature(req.temperature());
        entity.setTopP(req.topP());
        entity.setMaxTokens(req.maxTokens());
        entity.setExtraParametersJson(req.extraParametersJson());
        entity.setEnabled(req.enabled());
        entity.setRemark(req.remark());
        return entity;
    }
}
