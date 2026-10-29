package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.tool.AiToolTypes;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolProvider;
import com.astral.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具登记、绑定与执行入口。
 *
 * <p>启动时把代码里注册的 {@link AiToolHandler} 同步进 {@code ai_tool}：<b>代码是工具的唯一来源</b>，
 * 后台只能调整风险等级、确认策略与启停（设计文档 §11.5）。同步是单向的——
 * 代码新增的工具会自动出现，代码删除的工具会被标记为 DISABLED 而不是物理删除，避免历史审计断链。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolService {

    private final AiToolMapper toolMapper;
    private final AiAgentToolMapper agentToolMapper;
    private final List<AiToolProvider> providers;

    /** handlerKey → 处理器 */
    private final Map<String, AiToolHandler> handlers = new LinkedHashMap<>();

    /** toolCode → 处理器 */
    private final Map<String, AiToolHandler> byCode = new LinkedHashMap<>();

    @PostConstruct
    void init() {
        for (AiToolProvider provider : providers) {
            for (AiToolHandler handler : provider.handlers()) {
                AiToolDef def = handler.definition();
                if (def == null || def.toolCode() == null || def.handlerKey() == null) {
                    log.warn("[AI] 忽略非法的工具定义: {}", handler.getClass().getName());
                    continue;
                }
                if (handlers.containsKey(def.handlerKey())) {
                    log.warn("[AI] 重复的 handlerKey 被忽略: {}", def.handlerKey());
                    continue;
                }
                if (byCode.containsKey(def.toolCode())) {
                    log.warn("[AI] 重复的 toolCode 被忽略: {}", def.toolCode());
                    continue;
                }
                handlers.put(def.handlerKey(), handler);
                byCode.put(def.toolCode(), handler);
            }
        }
        log.info("[AI] 已登记工具处理器 {} 个", handlers.size());
    }

    // ==================== 登记同步 ====================

    /**
     * 把代码里的处理器同步到数据库。
     *
     * @param operatorId 操作人（可为空）
     * @return 新增/更新的工具数
     */
    @Transactional(rollbackFor = Exception.class)
    public int syncFromCode(Long operatorId) {
        int changed = 0;
        for (AiToolHandler handler : byCode.values()) {
            AiToolDef def = handler.definition();
            AiToolEntity entity = toolMapper.selectOne(new LambdaQueryWrapper<AiToolEntity>()
                    .eq(AiToolEntity::getToolCode, def.toolCode())
                    .last("limit 1"));
            boolean isNew = entity == null;
            if (isNew) {
                entity = new AiToolEntity();
                entity.setToolCode(def.toolCode());
                entity.setHandlerKey(def.handlerKey());
                entity.setCreatedBy(operatorId);
            }
            // 代码优先覆盖描述与 Schema，后台可调风险/确认/状态
            entity.setName(def.name());
            entity.setDescription(def.description());
            entity.setHandlerKey(def.handlerKey());
            entity.setClientTypes(AiJson.joinCsv(def.clientTypes()));
            entity.setPermissionCodes(AiJson.joinCsv(def.permissionCodes()));
            entity.setArgumentsSchema(def.argumentsSchema());
            entity.setResultSchema(def.resultSchema());
            entity.setSideEffects(def.sideEffects());
            if (isNew) {
                entity.setRiskLevel(def.riskLevel());
                entity.setApprovalPolicy(def.approvalPolicy());
                entity.setStatus(AiConstants.ToolStatus.ENABLED);
            } else {
                // 风险下限不可降低：代码要求 HIGH 的，后台不能配成 READ
                entity.setRiskLevel(strongerRisk(entity.getRiskLevel(), def.riskLevel()));
            }
            entity.setUpdatedBy(operatorId);
            if (isNew) {
                toolMapper.insert(entity);
            } else {
                toolMapper.updateById(entity);
            }
            changed++;
        }
        // 代码里已不存在的工具标记为禁用
        for (AiToolEntity entity : toolMapper.selectList(null)) {
            if (!byCode.containsKey(entity.getToolCode()) && !AiConstants.ToolStatus.DISABLED.equals(entity.getStatus())) {
                entity.setStatus(AiConstants.ToolStatus.DISABLED);
                toolMapper.updateById(entity);
                log.warn("[AI] 工具处理器已下线，置为 DISABLED: {}", entity.getToolCode());
            }
        }
        log.info("[AI] 工具登记同步完成，共 {} 个", changed);
        return changed;
    }

    /** 取更强的风险等级 */
    private static String strongerRisk(String a, String b) {
        return AiConstants.RiskLevel.order(a) >= AiConstants.RiskLevel.order(b) ? a : b;
    }

    // ==================== 查询/维护 ====================

    /** 全部工具 */
    public List<AiToolEntity> list() {
        return toolMapper.selectList(new LambdaQueryWrapper<AiToolEntity>()
                .orderByAsc(AiToolEntity::getId));
    }

    /** 取工具 */
    public AiToolEntity require(Long id) {
        AiToolEntity entity = id == null ? null : toolMapper.selectById(id);
        if (entity == null) {
            throw new BusinessException("AI008", "工具不存在: " + id);
        }
        return entity;
    }

    /** 按编码取工具 */
    public AiToolEntity findByCode(String toolCode) {
        if (toolCode == null || toolCode.isBlank()) {
            return null;
        }
        return toolMapper.selectOne(new LambdaQueryWrapper<AiToolEntity>()
                .eq(AiToolEntity::getToolCode, toolCode.trim())
                .last("limit 1"));
    }

    /** 后台编辑工具（只允许风险/确认/状态/名称/描述） */
    @Transactional(rollbackFor = Exception.class)
    public AiToolEntity update(Long id, String name, String description, String riskLevel,
                               String approvalPolicy, String status, Long operatorId) {
        AiToolEntity entity = require(id);
        AiToolHandler handler = handlers.get(entity.getHandlerKey());
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        if (description != null) {
            entity.setDescription(description);
        }
        String codeRisk = handler == null ? null : handler.definition().riskLevel();
        if (riskLevel != null && !riskLevel.isBlank()) {
            String target = riskLevel.toUpperCase(Locale.ROOT);
            if (codeRisk != null && AiConstants.RiskLevel.order(target) < AiConstants.RiskLevel.order(codeRisk)) {
                throw new BusinessException("AI008",
                        "不能把风险等级降低到代码要求之下（最低 " + codeRisk + "）");
            }
            entity.setRiskLevel(target);
        }
        if (approvalPolicy != null && !approvalPolicy.isBlank()) {
            String target = approvalPolicy.toUpperCase(Locale.ROOT);
            if (!AiConstants.ApprovalPolicy.all().contains(target)) {
                throw new BusinessException("AI008", "确认策略非法: " + target);
            }
            // 代码要求强确认的，后台不能放开
            if (codeRisk != null && AiConstants.RiskLevel.HIGH.equals(codeRisk)
                    && AiConstants.ApprovalPolicy.NONE.equals(target)) {
                throw new BusinessException("AI008", "高风险工具必须保留人工确认");
            }
            entity.setApprovalPolicy(target);
        }
        if (status != null && !status.isBlank()) {
            String target = status.toUpperCase(Locale.ROOT);
            if (!Set.of("ENABLED", "DISABLED").contains(target)) {
                throw new BusinessException("AI008", "工具状态非法: " + target);
            }
            entity.setStatus(target);
        }
        entity.setUpdatedBy(operatorId);
        toolMapper.updateById(entity);
        return entity;
    }

    /** 该工具是否有可执行处理器 */
    public boolean handlerAvailable(AiToolEntity entity) {
        return entity != null && handlers.containsKey(entity.getHandlerKey());
    }

    /** 取处理器 */
    public AiToolHandler handler(String handlerKey) {
        return handlers.get(handlerKey);
    }

    // ==================== 绑定 ====================

    /** 某智能体版本绑定的工具 */
    public List<AiAgentToolEntity> listBindings(Long agentVersionId) {
        return agentToolMapper.selectList(new LambdaQueryWrapper<AiAgentToolEntity>()
                .eq(AiAgentToolEntity::getAgentVersionId, agentVersionId)
                .orderByAsc(AiAgentToolEntity::getSort));
    }

    /** 整体替换某智能体版本的工具绑定 */
    @Transactional(rollbackFor = Exception.class)
    public void replaceBindings(Long agentVersionId, List<Long> toolIds, Long operatorId) {
        agentToolMapper.delete(new LambdaQueryWrapper<AiAgentToolEntity>()
                .eq(AiAgentToolEntity::getAgentVersionId, agentVersionId));
        if (toolIds == null) {
            return;
        }
        int sort = 0;
        for (Long toolId : toolIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            AiToolEntity tool = require(toolId);
            AiAgentToolEntity binding = new AiAgentToolEntity();
            binding.setAgentVersionId(agentVersionId);
            binding.setToolId(toolId);
            binding.setEnabled(true);
            binding.setSort(sort++);
            agentToolMapper.insert(binding);
        }
    }

    /** 新增单条绑定（带覆盖项） */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentToolEntity addBinding(Long agentVersionId, Long toolId, Boolean enabled, Integer sort,
                                        String riskOverride, String approvalPolicy) {
        AiToolEntity tool = require(toolId);
        if (riskOverride != null && !riskOverride.isBlank()) {
            if (AiConstants.RiskLevel.order(riskOverride) < AiConstants.RiskLevel.order(tool.getRiskLevel())) {
                throw new BusinessException("AI008", "风险覆盖只能提高不能降低");
            }
        }
        AiAgentToolEntity existing = agentToolMapper.selectOne(new LambdaQueryWrapper<AiAgentToolEntity>()
                .eq(AiAgentToolEntity::getAgentVersionId, agentVersionId)
                .eq(AiAgentToolEntity::getToolId, toolId)
                .last("limit 1"));
        AiAgentToolEntity binding = existing == null ? new AiAgentToolEntity() : existing;
        binding.setAgentVersionId(agentVersionId);
        binding.setToolId(toolId);
        binding.setEnabled(enabled == null || enabled);
        binding.setSort(sort == null ? 0 : sort);
        binding.setRiskOverride(riskOverride == null || riskOverride.isBlank() ? null : riskOverride.toUpperCase(Locale.ROOT));
        binding.setApprovalPolicy(approvalPolicy == null || approvalPolicy.isBlank()
                ? null : approvalPolicy.toUpperCase(Locale.ROOT));
        if (existing == null) {
            agentToolMapper.insert(binding);
        } else {
            agentToolMapper.updateById(binding);
        }
        return binding;
    }

    /** 删除单条绑定 */
    @Transactional(rollbackFor = Exception.class)
    public void removeBinding(Long agentVersionId, Long toolId) {
        agentToolMapper.delete(new LambdaQueryWrapper<AiAgentToolEntity>()
                .eq(AiAgentToolEntity::getAgentVersionId, agentVersionId)
                .eq(AiAgentToolEntity::getToolId, toolId));
    }

    /**
     * 解析某智能体版本可用的工具（已启用 + 客户端匹配 + 处理器存在）。
     *
     * @param agentVersionId 版本
     * @param clientType     客户端
     * @return 可用工具与其生效配置
     */
    public List<EffectiveTool> resolveEffective(Long agentVersionId, String clientType) {
        List<AiAgentToolEntity> bindings = listBindings(agentVersionId);
        List<EffectiveTool> result = new ArrayList<>();
        for (AiAgentToolEntity binding : bindings) {
            if (!Boolean.TRUE.equals(binding.getEnabled())) {
                continue;
            }
            AiToolEntity tool = toolMapper.selectById(binding.getToolId());
            if (tool == null || !"ENABLED".equals(tool.getStatus())) {
                continue;
            }
            AiToolHandler handler = handlers.get(tool.getHandlerKey());
            if (handler == null) {
                continue;
            }
            List<String> clients = AiJson.splitCsv(tool.getClientTypes());
            if (!clients.isEmpty() && clientType != null && !clients.contains(clientType)) {
                continue;
            }
            String risk = binding.getRiskOverride() == null ? tool.getRiskLevel()
                    : strongerRisk(tool.getRiskLevel(), binding.getRiskOverride());
            String approval = binding.getApprovalPolicy() == null ? tool.getApprovalPolicy()
                    : binding.getApprovalPolicy();
            result.add(new EffectiveTool(tool, handler, risk, approval, binding.getSort()));
        }
        result.sort(java.util.Comparator.comparing(t -> t.sort() == null ? 0 : t.sort()));
        return result;
    }

    /**
     * 生效工具。
     *
     * @param tool     工具记录
     * @param handler  处理器
     * @param risk     生效风险等级
     * @param approval 生效确认策略
     * @param sort     排序
     */
    public record EffectiveTool(AiToolEntity tool, AiToolHandler handler, String risk, String approval,
                                Integer sort) {
    }
}
