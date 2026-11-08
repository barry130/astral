package com.astral.ai.controller;

import com.astral.ai.common.AiCaller;
import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiChatOrchestrator;
import com.astral.ai.service.AiConversationService;
import com.astral.ai.service.AiRequestService;
import com.astral.ai.service.AiToolService;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
import com.astral.common.result.Result;
import com.astral.log.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 后台：工具登记、声明式工具维护与高风险工具审批。
 *
 * <p><b>代码工具</b>：本体由代码注册（{@code handlerKey} 指向已登记处理器），后台只能调整名称、
 * 说明、风险下限之上的等级、确认策略与启用状态——后台无法凭空创建代码处理器，这是设计文档
 * §14 的硬约束。</p>
 *
 * <p><b>声明式工具</b>：允许后台创建 {@code executor_type='HTTP'} 的工具，执行面被收窄在
 * {@link com.astral.ai.tool.declarative.HttpToolExecutor}（host 字面量 + 模板只出现在
 * path/query/header/body + SSRF 护栏），写方法强制 HIGH + ADMIN_CONFIRM，因此不会变成
 * 任意代码执行入口。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:config:view", name = "AI 配置查看", description = "AI 工具查看")
@RequiredArgsConstructor
public class AiAdminToolController {

    private final AiToolService toolService;
    private final AiToolCallMapper toolCallMapper;
    private final AiRequestService requestService;
    private final AiAgentService agentService;
    private final AiConversationService conversationService;
    private final AiChatOrchestrator orchestrator;

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

    // ==================== 工具 ====================

    @GetMapping("/tools")
    @RequiresPermission(value = "admin:ai:tool:view", name = "AI 工具查看", description = "查看已登记工具")
    public Result<List<AiAdminDtos.ToolVo>> tools() {
        List<AiAdminDtos.ToolVo> list = new ArrayList<>();
        for (AiToolEntity entity : toolService.list()) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    /**
     * 触发一次代码定义同步。
     *
     * <p>插件启动时已自动同步；此接口用于升级后不重启就补齐新增工具，或在排障时手动重放。</p>
     */
    @PostMapping("/tools/sync")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "同步代码工具定义")
    @OperateLog("同步 AI 工具定义")
    public Result<Map<String, Object>> syncTools(HttpServletRequest request) {
        int changed = toolService.syncFromCode(operatorId(request));
        return Result.success("同步完成", Map.of("changed", changed));
    }

    @PutMapping("/tools/{id}")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "修改工具配置")
    @OperateLog("修改 AI 工具配置")
    public Result<AiAdminDtos.ToolVo> updateTool(
            @PathVariable Long id, @Valid @RequestBody AiAdminDtos.ToolSaveReq req, HttpServletRequest request) {
        return Result.success(
                "保存成功",
                toVo(toolService.update(
                        id,
                        req.name(),
                        req.description(),
                        req.riskLevel(),
                        req.approvalPolicy(),
                        req.status(),
                        operatorId(request))));
    }

    // ==================== 声明式工具（HTTP / SQL） ====================

    /**
     * 新建声明式 HTTP 工具。
     *
     * <p>执行配置只允许「按模板调用一个 HTTP 接口」；写方法的风险与确认策略由服务端强制
     * HIGH + ADMIN_CONFIRM，传参无法放开。</p>
     */
    @PostMapping("/tools/http")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "新建声明式 HTTP 工具")
    @OperateLog("新建 AI 声明式 HTTP 工具")
    public Result<AiAdminDtos.ToolVo> createHttpTool(
            @Valid @RequestBody AiAdminDtos.DeclarativeToolSaveReq req, HttpServletRequest request) {
        return Result.success(
                "创建成功", toVo(toolService.createDeclarative(req, AiConstants.ExecutorType.HTTP, operatorId(request))));
    }

    /** 修改声明式 HTTP 工具（含执行配置），校验规则与新建一致 */
    @PutMapping("/tools/{id}/http")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "修改声明式 HTTP 工具")
    @OperateLog("修改 AI 声明式 HTTP 工具")
    public Result<AiAdminDtos.ToolVo> updateHttpTool(
            @PathVariable Long id,
            @Valid @RequestBody AiAdminDtos.DeclarativeToolSaveReq req,
            HttpServletRequest request) {
        return Result.success("保存成功", toVo(toolService.updateDeclarative(id, req, operatorId(request))));
    }

    /**
     * 新建声明式 SQL 工具。
     *
     * <p>只允许单条 SELECT / WITH … SELECT：写关键字（INSERT/UPDATE/DELETE/INTO/DDL…）、
     * 多语句、以及把入参拼进 SQL 的做法在保存与执行时都会被拒绝，参数一律 PreparedStatement 绑定。</p>
     */
    @PostMapping("/tools/sql")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "新建声明式 SQL 工具")
    @OperateLog("新建 AI 声明式 SQL 工具")
    public Result<AiAdminDtos.ToolVo> createSqlTool(
            @Valid @RequestBody AiAdminDtos.DeclarativeToolSaveReq req, HttpServletRequest request) {
        return Result.success(
                "创建成功", toVo(toolService.createDeclarative(req, AiConstants.ExecutorType.SQL, operatorId(request))));
    }

    /** 修改声明式 SQL 工具（含查询配置），校验规则与新建一致 */
    @PutMapping("/tools/{id}/sql")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "修改声明式 SQL 工具")
    @OperateLog("修改 AI 声明式 SQL 工具")
    public Result<AiAdminDtos.ToolVo> updateSqlTool(
            @PathVariable Long id,
            @Valid @RequestBody AiAdminDtos.DeclarativeToolSaveReq req,
            HttpServletRequest request) {
        return Result.success("保存成功", toVo(toolService.updateDeclarative(id, req, operatorId(request))));
    }

    /**
     * 用样例参数试跑工具。
     *
     * <p>仅超管可触发，且以真实操作者身份执行：停用工具拒绝、在册权限经与编排
     * {@code invoke} 同一套权限咽喉校验、每次试跑写 {@code ai_tool_call} 审计行
     * （{@code source='test-run'}）。手动触发等同「人工确认」，HIGH 工具直接执行。</p>
     */
    @PostMapping("/tools/{id}/test-run")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "试跑工具")
    @OperateLog("测试运行 AI 工具")
    public Result<AiAdminDtos.ToolTestRunVo> testRun(
            @PathVariable Long id, @Valid @RequestBody AiAdminDtos.ToolTestRunReq req, HttpServletRequest request) {
        long started = System.currentTimeMillis();
        AiToolResult result = toolService.testRun(id, req.arguments(), AiCaller.requireUserId(request));
        return Result.success(new AiAdminDtos.ToolTestRunVo(
                result.status(), result.summary(), result.payload(), System.currentTimeMillis() - started));
    }

    // ==================== 审批 ====================

    /**
     * 高风险工具确认。
     *
     * <p>通过后就地执行该工具，再唤醒原请求继续生成答复；拒绝同样唤醒，让模型以「用户拒绝」
     * 为前提收束回答。两条路径都会写审计日志。</p>
     */
    @PostMapping("/tool-calls/{id}/approve")
    @RequiresPermission(value = "admin:ai:tool:approve", name = "AI 工具审批", description = "确认高风险工具执行")
    @OperateLog("审批 AI 高风险工具")
    public Result<Map<String, Object>> approve(
            @PathVariable Long id, @Valid @RequestBody AiAdminDtos.ToolApproveReq req, HttpServletRequest request) {
        return Result.success(orchestrator.approve(
                id, AiCaller.requireUserId(request), Boolean.TRUE.equals(req.approved()), req.comment(), true));
    }

    private AiAdminDtos.ToolVo toVo(AiToolEntity entity) {
        return new AiAdminDtos.ToolVo(
                entity.getId(),
                entity.getToolCode(),
                entity.getName(),
                entity.getDescription(),
                entity.getHandlerKey(),
                AiToolService.executorTypeOf(entity),
                entity.getExecutorConfig(),
                AiJson.splitCsv(entity.getClientTypes()),
                AiJson.splitCsv(entity.getPermissionCodes()),
                entity.getRiskLevel(),
                entity.getApprovalPolicy(),
                entity.getArgumentsSchema(),
                entity.getResultSchema(),
                entity.getSideEffects(),
                entity.getStatus(),
                toolService.handlerAvailable(entity),
                entity.getCreateTime(),
                entity.getUpdateTime());
    }

    @PostMapping("/tool-calls/{id}/reconcile")
    @RequiresPermission(value = "admin:ai:tool:reconcile", name = "AI 工具结果核对", description = "记录未知操作的实际执行结果")
    @OperateLog(value = "核对 AI 工具结果", recordPayloads = false)
    public Result<Map<String, Object>> reconcile(
            @PathVariable Long id, @Valid @RequestBody AiAdminDtos.ToolReconcileReq body, HttpServletRequest request) {
        return Result.success(
                orchestrator.reconcileTool(id, AiCaller.requireUserId(request), body.outcome(), body.note()));
    }
}
