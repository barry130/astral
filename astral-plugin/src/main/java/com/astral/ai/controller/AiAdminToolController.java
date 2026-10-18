package com.astral.ai.controller;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiMessageEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiToolCallEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiToolCallMapper;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiChatOrchestrator;
import com.astral.ai.service.AiConversationService;
import com.astral.ai.service.AiRequestService;
import com.astral.ai.service.AiToolService;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.log.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 后台：工具登记与高风险工具审批。
 *
 * <p>工具本体由代码注册（{@code handlerKey} 指向已登记处理器），后台只能调整名称、说明、
 * 风险下限之上的等级、确认策略与启用状态。**后台无法凭空创建一个处理器**——这是设计文档
 * §14 的硬约束，避免配置面变成任意代码执行入口。</p>
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
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "同步代码工具定义")
    @OperateLog("同步 AI 工具定义")
    public Result<Map<String, Object>> syncTools(HttpServletRequest request) {
        int changed = toolService.syncFromCode(operatorId(request));
        return Result.success("同步完成", Map.of("changed", changed));
    }

    @PutMapping("/tools/{id}")
    @RequiresPermission(value = "admin:ai:tool:edit", name = "AI 工具编辑", description = "修改工具配置")
    @OperateLog("修改 AI 工具配置")
    public Result<AiAdminDtos.ToolVo> updateTool(@PathVariable Long id,
                                                 @Valid @RequestBody AiAdminDtos.ToolSaveReq req,
                                                 HttpServletRequest request) {
        return Result.success("保存成功", toVo(toolService.update(id, req.name(), req.description(),
                req.riskLevel(), req.approvalPolicy(), req.status(), operatorId(request))));
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
    public Result<Map<String, Object>> approve(@PathVariable Long id,
                                               @Valid @RequestBody AiAdminDtos.ToolApproveReq req,
                                               HttpServletRequest request) {
        AiToolCallEntity call = toolCallMapper.selectById(id);
        if (call == null) {
            throw new AiException("AI008", "工具调用不存在: " + id);
        }
        if (!AiConstants.ToolCallStatus.WAITING_APPROVAL.equals(call.getStatus())) {
            throw new AiException("AI008", "该工具调用不在待确认状态");
        }
        Long operator = operatorId(request);
        AiRequestEntity aiRequest = requestService.require(call.getRequestId());
        AiAgentEntity agent = agentService.require(aiRequest.getAgentId());
        AiAgentVersionEntity version = agentService.requireVersion(aiRequest.getAgentVersionId());

        boolean approved = Boolean.TRUE.equals(req.approved());
        call.setApprovedBy(operator);
        call.setApprovedAt(LocalDateTime.now());
        call.setApprovalStatus(approved ? AiConstants.ApprovalPolicy.ADMIN_CONFIRM
                : AiConstants.ToolCallStatus.REJECTED);

        String status;
        String summary;
        if (!approved) {
            call.setStatus(AiConstants.ToolCallStatus.REJECTED);
            call.setFinishedAt(LocalDateTime.now());
            call.setErrorMessage(req.comment() == null ? "管理员拒绝执行" : req.comment());
            toolCallMapper.updateById(call);
            status = AiConstants.ToolCallStatus.REJECTED;
            summary = "管理员已拒绝该操作";
        } else {
            AiToolService.EffectiveTool effective = resolve(version.getId(), aiRequest.getClientType(),
                    call.getToolCode());
            if (effective == null) {
                throw new AiException("AI008", "工具未绑定或已停用: " + call.getToolCode());
            }
            call.setStatus(AiConstants.ToolCallStatus.RUNNING);
            toolCallMapper.updateById(call);
            AiToolResult result = orchestrator.invoke(aiRequest, agent, effective,
                    AiJson.readObject(call.getArgumentsJson()), true, operator);
            call.setStatus(result.isOk() ? AiConstants.ToolCallStatus.SUCCEEDED
                    : AiConstants.ToolCallStatus.FAILED);
            call.setResultJson(result.payload() == null ? null : AiJson.write(result.payload()));
            call.setErrorMessage(result.isOk() ? null : result.summary());
            call.setFinishedAt(LocalDateTime.now());
            toolCallMapper.updateById(call);
            status = call.getStatus();
            summary = result.summary();
        }

        resume(aiRequest.getId());
        return Result.success(approved ? "已执行" : "已拒绝",
                Map.of("toolCallId", id, "status", status, "summary", summary == null ? "" : summary));
    }

    // ==================== 内部 ====================

    private AiToolService.EffectiveTool resolve(Long versionId, String clientType, String toolCode) {
        for (AiToolService.EffectiveTool effective : toolService.resolveEffective(versionId, clientType)) {
            if (effective.tool().getToolCode().equals(toolCode)) {
                return effective;
            }
        }
        return null;
    }

    private void resume(Long requestId) {
        AiMessageEntity assistant = null;
        for (AiMessageEntity message : conversationService.byRequest(requestId)) {
            if (AiConstants.MessageRole.ASSISTANT.equals(message.getRole())) {
                assistant = message;
            }
        }
        if (assistant == null) {
            throw new BusinessException("AI007", "找不到待恢复的助手消息");
        }
        orchestrator.resume(requestId, assistant.getId());
    }

    private AiAdminDtos.ToolVo toVo(AiToolEntity entity) {
        return new AiAdminDtos.ToolVo(entity.getId(), entity.getToolCode(), entity.getName(),
                entity.getDescription(), entity.getHandlerKey(), AiJson.splitCsv(entity.getClientTypes()),
                AiJson.splitCsv(entity.getPermissionCodes()), entity.getRiskLevel(), entity.getApprovalPolicy(),
                entity.getArgumentsSchema(), entity.getResultSchema(), entity.getSideEffects(), entity.getStatus(),
                toolService.handlerAvailable(entity), entity.getCreateTime(), entity.getUpdateTime());
    }
}
