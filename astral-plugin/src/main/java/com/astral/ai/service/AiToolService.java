package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentToolEntity;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.entity.AiToolCallEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentToolMapper;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.security.AiToolPermissionGate;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolProvider;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.ai.tool.declarative.HttpToolExecutor;
import com.astral.ai.tool.declarative.McpToolExecutor;
import com.astral.ai.tool.declarative.SqlToolExecutor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

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

    /** 工具执行期权限咽喉（与编排 invoke 共用同一套校验，不复制两份） */
    private final AiToolPermissionGate toolPermissionGate;

    /** 试跑审计（ai_tool_call，source='test-run'） */
    private final AiToolCallMapper toolCallMapper;

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
            if (fromCode
                    && !byCode.containsKey(entity.getToolCode())
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
        return toolMapper.selectList(new LambdaQueryWrapper<AiToolEntity>().orderByAsc(AiToolEntity::getId));
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
    public AiToolEntity update(
            Long id,
            String name,
            String description,
            String riskLevel,
            String approvalPolicy,
            String status,
            Long operatorId) {
        AiToolEntity entity = require(id);
        if (name != null && !name.isBlank()) {
            entity.setName(name.trim());
        }
        if (description != null) {
            entity.setDescription(description);
        }
        String[] floor = riskFloor(entity);
        String floorRisk = floor[0];
        String floorApproval = floor[1];
        if (riskLevel != null && !riskLevel.isBlank()) {
            String target = riskLevel.toUpperCase(Locale.ROOT);
            if (!AiConstants.RiskLevel.all().contains(target)) {
                throw new AiException("AI008", "风险等级非法: " + target);
            }
            if (AiConstants.RiskLevel.order(target) < AiConstants.RiskLevel.order(floorRisk)) {
                throw new AiException("AI008", "不能把风险等级降低到执行器要求之下（最低 " + floorRisk + "）");
            }
            entity.setRiskLevel(target);
        }
        if (approvalPolicy != null && !approvalPolicy.isBlank()) {
            String target = approvalPolicy.toUpperCase(Locale.ROOT);
            if (!AiConstants.ApprovalPolicy.all().contains(target)) {
                throw new AiException("AI008", "确认策略非法: " + target);
            }
            // 执行器要求强确认的，后台不能放开
            if (AiConstants.RiskLevel.HIGH.equals(floorRisk)
                    && !AiConstants.ApprovalPolicy.ADMIN_CONFIRM.equals(target)) {
                throw new AiException("AI008", "高风险工具必须保留人工确认（管理员确认）");
            }
            // floor 是二元组：非 HIGH 的声明确认（如 DRAFT + USER_CONFIRM）同样不能被清空
            if (AiConstants.ApprovalPolicy.order(target) < AiConstants.ApprovalPolicy.order(floorApproval)) {
                throw new AiException("AI008", "不能把确认策略降低到执行器要求之下（最低 " + floorApproval + "）");
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
                ? AiConstants.ExecutorType.CODE
                : entity.getExecutorType();
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
    public AiToolEntity createDeclarative(
            AiAdminDtos.DeclarativeToolSaveReq req, String executorType, Long operatorId) {
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
                ? entity.getToolCode()
                : req.toolCode().trim();
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
     * 测试运行：以真实操作者身份直接执行一次工具。
     *
     * <p>入口仅限超管（{@code @RequiresSuper}），但执行面不做合成旁路：停用工具拒绝执行；
     * 执行前经 {@link AiToolPermissionGate}——与编排 {@code invoke} 同一套校验——核对操作者的
     * 在册权限，清空 {@code permission_codes} 也拿不掉代码注册的权限下限。超管手动试跑等同于
     * 「人工确认」（显式旁路仅此入口：{@code approved=true} 且 {@code approvedBy} 记录真实操作者），
     * HIGH 工具因此可直接执行。</p>
     *
     * <p>每次试跑写一条 {@code ai_tool_call} 审计行（{@code source='test-run'}，
     * 不归属任何 {@code ai_request}），留痕不再依赖操作日志。</p>
     *
     * @param id            工具
     * @param argumentsJson 参数 JSON 文本
     * @param operatorId    触发试跑的管理员（{@code AiCaller.requireUserId} 取值）
     * @return 执行结果
     */
    public AiToolResult testRun(Long id, String argumentsJson, Long operatorId) {
        AiToolEntity entity = require(id);
        if (!AiConstants.ToolStatus.ENABLED.equals(entity.getStatus())) {
            throw new AiException("AI008", "工具已停用: " + entity.getToolCode());
        }
        AiToolHandler handler = resolveHandler(entity);
        if (handler == null) {
            throw new AiException("AI008", "工具没有可执行处理器: " + entity.getToolCode());
        }
        AiToolPermissionGate.Outcome outcome = toolPermissionGate.check(operatorId, entity, handler);
        if (outcome == AiToolPermissionGate.Outcome.MALFORMED) {
            return AiToolResult.rejected("工具权限配置异常，已拒绝执行");
        }
        if (outcome == AiToolPermissionGate.Outcome.DENIED) {
            return AiToolResult.rejected("无权限调用工具");
        }
        ObjectNode arguments = argumentsJson == null || argumentsJson.isBlank()
                ? AiJson.mapper().createObjectNode()
                : AiJson.readObject(argumentsJson);
        if (arguments == null) {
            arguments = AiJson.mapper().createObjectNode();
        }
        LocalDateTime now = LocalDateTime.now();
        AiToolCallEntity record = new AiToolCallEntity();
        record.setToolCode(entity.getToolCode());
        record.setToolName(entity.getName());
        record.setRiskLevel(entity.getRiskLevel());
        record.setArgumentsJson(argumentsJson);
        record.setSource(AiConstants.ToolCallSource.TEST_RUN);
        record.setStatus(AiConstants.ToolCallStatus.RUNNING);
        record.setApprovalStatus(AiConstants.ApprovalPolicy.NONE);
        record.setApprovedBy(operatorId);
        record.setApprovedAt(now);
        record.setStartedAt(now);
        toolCallMapper.insert(record);
        AiToolContext context = new AiToolContext(
                null,
                null,
                operatorId,
                null,
                "test-run",
                AiConstants.AgentType.CUSTOM,
                AiConstants.ClientType.ASTRAL_FRONT,
                arguments,
                AiJson.mapper().createObjectNode(),
                true,
                operatorId);
        AiToolResult result;
        try {
            result = handler.execute(context);
        } catch (AiException e) {
            result = AiToolResult.failed(e.getMessage());
        } catch (Exception e) {
            log.warn("[AI] 试跑执行失败 tool={}: {}", entity.getToolCode(), e.getMessage());
            result = AiToolResult.failed(e.getMessage() == null ? "工具执行失败" : e.getMessage());
        }
        if (result.isOk()) {
            record.setStatus(AiConstants.ToolCallStatus.SUCCEEDED);
        } else if (AiToolResult.REJECTED.equals(result.status())) {
            record.setStatus(AiConstants.ToolCallStatus.REJECTED);
        } else {
            record.setStatus(AiConstants.ToolCallStatus.FAILED);
        }
        record.setResultJson(result.payload() == null ? null : AiJson.write(result.payload()));
        record.setErrorMessage(result.isOk() ? null : result.summary());
        record.setFinishedAt(LocalDateTime.now());
        toolCallMapper.updateById(record);
        return result;
    }

    /** 执行器类型归一化并校验 */
    private static String normalizeExecutorType(String executorType) {
        String type = executorType == null
                ? AiConstants.ExecutorType.CODE
                : executorType.trim().toUpperCase(Locale.ROOT);
        if (!AiConstants.ExecutorType.all().contains(type)) {
            throw new AiException("AI008", "执行器类型非法: " + executorType);
        }
        return type;
    }

    /** 声明式工具的 handlerKey（固定值，与代码处理器命名空间隔离） */
    private static String handlerKeyOf(String type) {
        return AiConstants.ExecutorType.HTTP.equals(type) ? HttpToolExecutor.HANDLER_KEY : SqlToolExecutor.HANDLER_KEY;
    }

    /** 按执行器类型做保存期校验（执行配置 + 参数占位符 + 安全护栏） */
    private void validateDeclarative(String type, AiAdminDtos.DeclarativeToolSaveReq req) {
        if (AiConstants.ExecutorType.HTTP.equals(type)) {
            HttpToolExecutor.validate(req.executorConfig(), req.argumentsSchema(), allowedHosts());
        } else if (AiConstants.ExecutorType.SQL.equals(type)) {
            SqlToolExecutor.validate(req.executorConfig(), req.argumentsSchema(), properties);
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
        entity.setApprovalPolicy(
                readOnly ? approvalOrDefault(req.approvalPolicy()) : AiConstants.ApprovalPolicy.ADMIN_CONFIRM);
        entity.setSideEffects(readOnly ? "只读调用外部 HTTP 接口" : "调用外部 HTTP 接口（可能产生写操作）");
    }

    /**
     * 后台编辑工具时允许的最低风险与最低确认策略。
     *
     * <p>代码工具来自 handler 定义；声明式工具来自执行配置本身：非 GET 的 HTTP 工具与可写的 MCP 工具
     * 强制 HIGH + ADMIN_CONFIRM，SQL 执行器只读所以下限是 READ + NONE。后台只能抬高，不能降低。</p>
     */
    private String[] riskFloor(AiToolEntity entity) {
        String type = executorTypeOf(entity);
        if (AiConstants.ExecutorType.HTTP.equals(type)) {
            return "GET".equals(methodOf(entity.getExecutorConfig()))
                    ? new String[] {AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE}
                    : new String[] {AiConstants.RiskLevel.HIGH, AiConstants.ApprovalPolicy.ADMIN_CONFIRM};
        }
        if (AiConstants.ExecutorType.MCP.equals(type)) {
            Long serverId = McpToolExecutor.serverIdOf(entity);
            AiMcpServerEntity server = serverId == null ? null : mcpServerMapper.selectById(serverId);
            boolean readOnly = server != null
                    && McpToolExecutor.readOnlyOf(
                            Boolean.TRUE.equals(server.getReadOnly()), McpToolExecutor.readOnlyHintOf(entity));
            return readOnly
                    ? new String[] {AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE}
                    : new String[] {AiConstants.RiskLevel.HIGH, AiConstants.ApprovalPolicy.ADMIN_CONFIRM};
        }
        if (AiConstants.ExecutorType.SQL.equals(type)) {
            return new String[] {AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE};
        }
        AiToolHandler handler = resolveHandler(entity);
        if (handler == null) {
            return new String[] {AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE};
        }
        return new String[] {
            handler.definition().riskLevel(), handler.definition().approvalPolicy()
        };
    }

    private static String methodOf(String executorConfig) {
        return AiJson.text(AiJson.readObject(executorConfig), "method", "GET").toUpperCase(Locale.ROOT);
    }

    private static String riskOrDefault(String riskLevel) {
        String target = riskLevel == null || riskLevel.isBlank()
                ? AiConstants.RiskLevel.READ
                : riskLevel.toUpperCase(Locale.ROOT);
        if (!AiConstants.RiskLevel.all().contains(target)) {
            throw new AiException("AI008", "风险等级非法: " + target);
        }
        return target;
    }

    private static String approvalOrDefault(String approvalPolicy) {
        String target = approvalPolicy == null || approvalPolicy.isBlank()
                ? AiConstants.ApprovalPolicy.NONE
                : approvalPolicy.toUpperCase(Locale.ROOT);
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
        requireDraftBinding(agentVersionId);
        agentToolMapper.delete(
                new LambdaQueryWrapper<AiAgentToolEntity>().eq(AiAgentToolEntity::getAgentVersionId, agentVersionId));
        if (toolIds == null) {
            return;
        }
        int sort = 0;
        for (Long toolId :
                toolIds.stream().filter(java.util.Objects::nonNull).distinct().toList()) {
            AiToolEntity tool = require(toolId);
            AiAgentToolEntity binding = new AiAgentToolEntity();
            binding.setAgentVersionId(agentVersionId);
            binding.setToolId(toolId);
            binding.setEnabled(true);
            binding.setSort(sort++);
            agentToolMapper.insert(binding);
        }
    }

    /**
     * 新增单条绑定（带覆盖项）。
     *
     * <p>覆盖项只能收紧不能放宽：riskOverride 不得低于执行器 floor 与工具当前风险等级的较高者；
     * approvalPolicy 不得低于执行器 floor 确认下限与工具当前确认策略的较高者
     * （绑定把「声明要确认」的工具覆盖成 NONE 会让它真的免确认执行）。
     * 非法值一律 AI008。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public AiAgentToolEntity addBinding(
            Long agentVersionId,
            Long toolId,
            Boolean enabled,
            Integer sort,
            String riskOverride,
            String approvalPolicy) {
        requireDraftBinding(agentVersionId);
        AiToolEntity tool = require(toolId);
        String[] floor = riskFloor(tool);
        if (riskOverride != null && !riskOverride.isBlank()) {
            String target = riskOverride.toUpperCase(Locale.ROOT);
            if (!AiConstants.RiskLevel.all().contains(target)) {
                throw new AiException("AI008", "风险等级非法: " + target);
            }
            int floorRiskOrder =
                    Math.max(AiConstants.RiskLevel.order(floor[0]), AiConstants.RiskLevel.order(tool.getRiskLevel()));
            if (AiConstants.RiskLevel.order(target) < floorRiskOrder) {
                throw new AiException("AI008", "风险覆盖只能提高不能降低（执行器最低 " + floor[0] + "）");
            }
        }
        if (approvalPolicy != null && !approvalPolicy.isBlank()) {
            String target = approvalPolicy.toUpperCase(Locale.ROOT);
            if (!AiConstants.ApprovalPolicy.all().contains(target)) {
                throw new AiException("AI008", "确认策略非法: " + target);
            }
            int floorApprovalOrder = Math.max(
                    AiConstants.ApprovalPolicy.order(floor[1]),
                    AiConstants.ApprovalPolicy.order(tool.getApprovalPolicy()));
            if (AiConstants.ApprovalPolicy.order(target) < floorApprovalOrder) {
                throw new AiException("AI008", "确认策略覆盖只能提高不能降低");
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
        binding.setRiskOverride(
                riskOverride == null || riskOverride.isBlank() ? null : riskOverride.toUpperCase(Locale.ROOT));
        binding.setApprovalPolicy(
                approvalPolicy == null || approvalPolicy.isBlank() ? null : approvalPolicy.toUpperCase(Locale.ROOT));
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
        requireDraftBinding(agentVersionId);
        agentToolMapper.delete(new LambdaQueryWrapper<AiAgentToolEntity>()
                .eq(AiAgentToolEntity::getAgentVersionId, agentVersionId)
                .eq(AiAgentToolEntity::getToolId, toolId));
    }

    private void requireDraftBinding(Long versionId) {
        if (!AiConstants.VersionStatus.DRAFT.equals(agentToolMapper.lockVersionStatus(versionId))) {
            throw new AiException("AI007", "只能修改现有草稿版本的工具绑定");
        }
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
            String risk = binding.getRiskOverride() == null
                    ? tool.getRiskLevel()
                    : strongerRisk(tool.getRiskLevel(), binding.getRiskOverride());
            String approval =
                    binding.getApprovalPolicy() == null ? tool.getApprovalPolicy() : binding.getApprovalPolicy();
            var definition = handler.definition();
            risk = strongerRisk(risk, definition.riskLevel());
            if (AiConstants.ApprovalPolicy.order(approval)
                    < AiConstants.ApprovalPolicy.order(tool.getApprovalPolicy())) {
                approval = tool.getApprovalPolicy();
            }
            if (AiConstants.ApprovalPolicy.order(approval)
                    < AiConstants.ApprovalPolicy.order(definition.approvalPolicy())) {
                approval = definition.approvalPolicy();
            }
            if (AiConstants.RiskLevel.HIGH.equals(risk)) {
                approval = AiConstants.ApprovalPolicy.ADMIN_CONFIRM;
            }
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
    /**
     * 计划期配置快照哈希：SHA-256("{executor_config}|{risk}|{approval}")。
     *
     * <p>覆盖后台在计划与审批之间可修改的三项——工具声明 executor_config 与绑定生效
     * risk/approval。计划建 WAITING_APPROVAL 行时写入，审批时重算比对，防 TOCTOU
     * （审计 2026-11-07 backend-ai-read-recovery R3）。null 一律按空串拼接。</p>
     */
    public static String planHash(AiToolEntity tool, String risk, String approval) {
        String material = (tool == null || tool.getExecutorConfig() == null ? "" : tool.getExecutorConfig())
                + "|"
                + (risk == null ? "" : risk)
                + "|"
                + (approval == null ? "" : approval);
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new AiException("AI008", "SHA-256 摘要不可用: " + e.getMessage());
        }
    }

    public static String planHash(EffectiveTool effective) {
        if (effective.handler() instanceof McpToolExecutor mcp) {
            AiToolEntity snapshot = new AiToolEntity();
            snapshot.setExecutorConfig(
                    planHash(effective.tool(), effective.risk(), effective.approval()) + ":" + mcp.planFingerprint());
            return planHash(snapshot, effective.risk(), effective.approval());
        }
        return planHash(effective.tool(), effective.risk(), effective.approval());
    }

    public record EffectiveTool(AiToolEntity tool, AiToolHandler handler, String risk, String approval, Integer sort) {}
}
