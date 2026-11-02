package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolProvider;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.ai.tool.declarative.HttpToolExecutor;
import com.astral.ai.tool.declarative.McpToolExecutor;
import com.astral.ai.tool.declarative.SqlToolExecutor;
import com.astral.ai.common.AiException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 工具登记、绑定与执行入口。
 *
 * <p><b>代码工具</b>（{@code executor_type='CODE'}）的唯一来源是代码：启动时把注册的
 * {@link AiToolHandler} 同步进 {@code ai_tool}，后台只能调整风险等级、确认策略与启停
 * （设计文档 §11.5），代码删除的工具被标记为 DISABLED 而不是物理删除，避免审计断链。</p>
 *
 * <p><b>声明式工具</b>（{@code executor_type='HTTP'}）由后台创建，执行面收敛在
 * {@link HttpToolExecutor}：只能表达「按模板调用一个 HTTP 接口」，无法表达任意代码。
 * 写方法（POST/PUT/PATCH/DELETE）强制 HIGH + ADMIN_CONFIRM，服务端写死、不可配置放开。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiToolService {

    private final AiToolMapper toolMapper;
    private final AiAgentToolMapper agentToolMapper;
    private final List<AiToolProvider> providers;
    private final AiProperties properties;
    /** 声明式 SQL 工具的数据源（当前环境无 JDBC 时工具视为不可用） */
    private final JdbcTemplate jdbcTemplate;

    /** MCP 服务器（声明式 MCP 工具按 serverId 取远端地址） */
    private final AiMcpServerMapper mcpServerMapper;

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
        // 代码里已不存在的「代码工具」标记为禁用；声明式工具由后台维护，不能在这里误杀
        for (AiToolEntity entity : toolMapper.selectList(null)) {
            boolean fromCode = AiConstants.ExecutorType.CODE.equals(executorTypeOf(entity));
            if (fromCode && !byCode.containsKey(entity.getToolCode())
                    && !AiConstants.ToolStatus.DISABLED.equals(entity.getStatus())) {
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
            throw new AiException("AI008", "工具不存在: " + id);
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
                throw new AiException("AI008",
                        "不能把风险等级降低到代码要求之下（最低 " + codeRisk + "）");
            }
            entity.setRiskLevel(target);
        }
        if (approvalPolicy != null && !approvalPolicy.isBlank()) {
            String target = approvalPolicy.toUpperCase(Locale.ROOT);
            if (!AiConstants.ApprovalPolicy.all().contains(target)) {
                throw new AiException("AI008", "确认策略非法: " + target);
            }
            // 代码要求强确认的，后台不能放开
            if (codeRisk != null && AiConstants.RiskLevel.HIGH.equals(codeRisk)
                    && AiConstants.ApprovalPolicy.NONE.equals(target)) {
                throw new AiException("AI008", "高风险工具必须保留人工确认");
            }
            entity.setApprovalPolicy(target);
        }
        if (status != null && !status.isBlank()) {
            String target = status.toUpperCase(Locale.ROOT);
            if (!Set.of("ENABLED", "DISABLED").contains(target)) {
                throw new AiException("AI008", "工具状态非法: " + target);
            }
            entity.setStatus(target);
        }
        entity.setUpdatedBy(operatorId);
        toolMapper.updateById(entity);
        return entity;
    }

    /** 该工具是否有可执行处理器（代码注册或声明式执行器） */
    public boolean handlerAvailable(AiToolEntity entity) {
        return resolveHandler(entity) != null;
    }

    /** 取处理器 */
    public AiToolHandler handler(String handlerKey) {
        return handlers.get(handlerKey);
    }

    /** 工具的执行器类型（空值按 CODE 处理，兼容迁移前的存量行） */
    public static String executorTypeOf(AiToolEntity entity) {
        return entity == null || entity.getExecutorType() == null
                ? AiConstants.ExecutorType.CODE : entity.getExecutorType();
    }

    /**
     * 取工具的可执行处理器：代码工具走注册表，声明式工具现构造执行器。
     *
     * <p>声明式执行配置非法时返回 {@code null}（与「处理器缺失」同样跳过并告警），
     * 避免一条坏配置拖垮整轮对话。</p>
     */
    public AiToolHandler resolveHandler(AiToolEntity entity) {
        if (entity == null) {
            return null;
        }
        if (AiConstants.ExecutorType.HTTP.equals(executorTypeOf(entity))) {
            return HttpToolExecutor.create(entity, properties);
        }
        if (AiConstants.ExecutorType.SQL.equals(executorTypeOf(entity))) {
            return SqlToolExecutor.create(entity, jdbcTemplate, properties);
        }
        if (AiConstants.ExecutorType.MCP.equals(executorTypeOf(entity))) {
            Long serverId = McpToolExecutor.serverIdOf(entity);
            AiMcpServerEntity server = serverId == null ? null : mcpServerMapper.selectById(serverId);
            return McpToolExecutor.create(entity, server, properties);
        }
        return handlers.get(entity.getHandlerKey());
    }

    // ==================== 声明式工具（HTTP / SQL） ====================

    /**
     * 新建声明式工具。
     *
     * <p>执行面收窄在 {@link HttpToolExecutor} / {@link SqlToolExecutor} 里，服务端负责三件事：
     * 校验执行配置、按执行器类型写 handlerKey、把「写操作必须人工确认」这条下限钉死——HTTP 的
     * 写方法强制 HIGH + ADMIN_CONFIRM，SQL 只允许只读查询。toolCode 与代码工具同表唯一。</p>
     *
     * @param req          保存请求
     * @param executorType 执行器类型（HTTP / SQL）
     * @param operatorId   操作人
     * @return 落库后的工具
     */
    @Transactional(rollbackFor = Exception.class)
    public AiToolEntity createDeclarative(AiAdminDtos.DeclarativeToolSaveReq req, String executorType,
                                          Long operatorId) {
        String type = normalizeExecutorType(executorType);
        String toolCode = req.toolCode() == null ? "" : req.toolCode().trim();
        validateDeclarative(type, req);
        if (findByCode(toolCode) != null) {
            throw new AiException("AI008", "工具编码已存在: " + toolCode);
        }
        AiToolEntity entity = new AiToolEntity();
        entity.setToolCode(toolCode);
        entity.setHandlerKey(handlerKeyOf(type));
        entity.setExecutorType(type);
        applyDeclarative(entity, req, type);
        entity.setStatus(AiConstants.ToolStatus.ENABLED);
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        toolMapper.insert(entity);
        log.info("[AI] 新建声明式 {} 工具 {}（{}）", type, toolCode, entity.getRiskLevel());
        return entity;
    }

    /**
     * 修改声明式工具（含执行配置），校验规则与新建一致。
     *
     * @param id          工具
     * @param req         保存请求
     * @param operatorId  操作人
     * @return 落库后的工具
     */
    @Transactional(rollbackFor = Exception.class)
    public AiToolEntity updateDeclarative(Long id, AiAdminDtos.DeclarativeToolSaveReq req, Long operatorId) {
        AiToolEntity entity = require(id);
        String type = normalizeExecutorType(executorTypeOf(entity));
        if (AiConstants.ExecutorType.CODE.equals(type)) {
            throw new AiException("AI008", "代码注册的工具不能按声明式配置修改: " + id);
        }
        String toolCode = req.toolCode() == null || req.toolCode().isBlank()
                ? entity.getToolCode() : req.toolCode().trim();
        validateDeclarative(type, req);
        AiToolEntity sameCode = findByCode(toolCode);
        if (sameCode != null && !sameCode.getId().equals(entity.getId())) {
            throw new AiException("AI008", "工具编码已存在: " + toolCode);
        }
        entity.setToolCode(toolCode);
        applyDeclarative(entity, req, type);
        entity.setUpdatedBy(operatorId);
        toolMapper.updateById(entity);
        log.info("[AI] 修改声明式 {} 工具 {}（{}）", type, toolCode, entity.getRiskLevel());
        return entity;
    }

    /**
     * 测试运行：用样例参数直接执行一次工具。
     *
     * <p>不写 {@code ai_tool_call} 审计表——它不属于任何会话，留痕交给后台操作日志；
     * 由管理员手动触发，等同于「人工确认」，因此 HIGH 工具也直接执行。</p>
     *
     * @param id            工具
     * @param argumentsJson 参数 JSON 文本
     * @return 执行结果
     */
    public AiToolResult testRun(Long id, String argumentsJson) {
        AiToolEntity entity = require(id);
        AiToolHandler handler = resolveHandler(entity);
        if (handler == null) {
            throw new AiException("AI008", "工具没有可执行处理器: " + entity.getToolCode());
        }
        ObjectNode arguments = argumentsJson == null || argumentsJson.isBlank()
                ? AiJson.mapper().createObjectNode() : AiJson.readObject(argumentsJson);
        if (arguments == null) {
            arguments = AiJson.mapper().createObjectNode();
        }
        AiToolContext context = new AiToolContext(null, null, null, null, "test-run",
                AiConstants.AgentType.CUSTOM, AiConstants.ClientType.ASTRAL_FRONT, arguments,
                AiJson.mapper().createObjectNode(), true, null);
        return handler.execute(context);
    }

    /** 执行器类型归一化并校验 */
    private static String normalizeExecutorType(String executorType) {
        String type = executorType == null ? AiConstants.ExecutorType.CODE
                : executorType.trim().toUpperCase(Locale.ROOT);
        if (!AiConstants.ExecutorType.all().contains(type)) {
            throw new AiException("AI008", "执行器类型非法: " + executorType);
        }
        return type;
    }

    /** 声明式工具的 handlerKey（固定值，与代码处理器命名空间隔离） */
    private static String handlerKeyOf(String type) {
        return AiConstants.ExecutorType.HTTP.equals(type)
                ? HttpToolExecutor.HANDLER_KEY : SqlToolExecutor.HANDLER_KEY;
    }

    /** 按执行器类型做保存期校验（执行配置 + 参数占位符 + 安全护栏） */
    private void validateDeclarative(String type, AiAdminDtos.DeclarativeToolSaveReq req) {
        if (AiConstants.ExecutorType.HTTP.equals(type)) {
            HttpToolExecutor.validate(req.executorConfig(), req.argumentsSchema(), allowedHosts());
        } else if (AiConstants.ExecutorType.SQL.equals(type)) {
            SqlToolExecutor.validate(req.executorConfig(), req.argumentsSchema());
        } else {
            throw new AiException("AI008", "该执行器类型不支持手工创建: " + type);
        }
    }

    /** 声明式工具字段落库（风险下限按执行器类型写死，不可由入参放开） */
    private void applyDeclarative(AiToolEntity entity, AiAdminDtos.DeclarativeToolSaveReq req, String type) {
        entity.setName(req.name() == null ? "" : req.name().trim());
        entity.setDescription(req.description());
        entity.setClientTypes(AiJson.joinCsv(req.clientTypes()));
        entity.setPermissionCodes(AiJson.joinCsv(req.permissionCodes()));
        entity.setArgumentsSchema(req.argumentsSchema());
        entity.setExecutorConfig(req.executorConfig());
        if (AiConstants.ExecutorType.SQL.equals(type)) {
            // SQL 工具只读：风险与确认策略由后台选，但下限仍是「只读查询」
            entity.setRiskLevel(riskOrDefault(req.riskLevel()));
            entity.setApprovalPolicy(approvalOrDefault(req.approvalPolicy()));
            entity.setSideEffects("只读 SQL 查询（连接只读 + 只允许单条 SELECT）");
            return;
        }
        String method = methodOf(req.executorConfig());
        boolean readOnly = "GET".equals(method);
        entity.setRiskLevel(readOnly ? riskOrDefault(req.riskLevel()) : AiConstants.RiskLevel.HIGH);
        entity.setApprovalPolicy(readOnly ? approvalOrDefault(req.approvalPolicy())
                : AiConstants.ApprovalPolicy.ADMIN_CONFIRM);
        entity.setSideEffects(readOnly ? "只读调用外部 HTTP 接口" : "调用外部 HTTP 接口（可能产生写操作）");
    }

    private static String methodOf(String executorConfig) {
        return AiJson.text(AiJson.readObject(executorConfig), "method", "GET").toUpperCase(Locale.ROOT);
    }

    private static String riskOrDefault(String riskLevel) {
        String target = riskLevel == null || riskLevel.isBlank()
                ? AiConstants.RiskLevel.READ : riskLevel.toUpperCase(Locale.ROOT);
        if (!AiConstants.RiskLevel.all().contains(target)) {
            throw new AiException("AI008", "风险等级非法: " + target);
        }
        return target;
    }

    private static String approvalOrDefault(String approvalPolicy) {
        String target = approvalPolicy == null || approvalPolicy.isBlank()
                ? AiConstants.ApprovalPolicy.NONE : approvalPolicy.toUpperCase(Locale.ROOT);
        if (!AiConstants.ApprovalPolicy.all().contains(target)) {
            throw new AiException("AI008", "确认策略非法: " + target);
        }
        return target;
    }

    private List<String> allowedHosts() {
        return properties.getHttpTool().getAllowedHosts();
    }


    // ==================== 绑定 ====================

    /**
     * 某智能体版本绑定的工具。
     *
     * @param agentVersionId 版本
     * @return 绑定列表（按排序号）
     */
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
                throw new AiException("AI008", "风险覆盖只能提高不能降低");
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
            AiToolHandler handler = resolveHandler(tool);
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
