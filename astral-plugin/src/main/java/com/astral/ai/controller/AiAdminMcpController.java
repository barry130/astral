package com.astral.ai.controller;

import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.security.AiMcpHeaderSecurity;
import com.astral.ai.service.AiMcpServerService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
import com.astral.common.result.Result;
import com.astral.log.annotation.OperateLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 后台：MCP 服务器管理。
 *
 * <p>管理的是一个 MCP 端点（Streamable HTTP）与它的工具发现结果。工具本体不在这里创建——
 * 「刷新」会把远端 {@code tools/list} 的结果写进 {@code ai_tool}，之后与其它声明式工具一样
 * 在工具页和智能体版本里绑定。远端工具的风险在发现阶段定死：只读服务器给 READ，
 * 其余一律 HIGH + ADMIN_CONFIRM。</p>
 *
 * <p>R9 安全边界：headers 中的敏感键（authorization / api-key / token / secret）落库前经
 * {@link AiMcpHeaderSecurity} 以 AES-GCM 加密、回显一律脱敏；新建/修改（可改端点指向、可携带
 * 本地凭据）仅超管可执行，删除与刷新沿用 {@code admin:ai:mcp:edit}——刷新只使用已存配置，
 * HTTP 面不携带目标地址或请求头。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:mcp:view", name = "MCP 服务器查看", description = "MCP 服务器查看")
@RequiredArgsConstructor
public class AiAdminMcpController {

    private final AiMcpServerService mcpServerService;

    private static Long operatorId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(userId));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 服务器列表 */
    @GetMapping("/mcp-servers")
    public Result<List<AiAdminDtos.McpServerVo>> listServers() {
        List<AiAdminDtos.McpServerVo> result = new ArrayList<>();
        for (AiMcpServerEntity entity : mcpServerService.list()) {
            result.add(toVo(entity));
        }
        return Result.success(result);
    }

    /** 新建服务器（不自动刷新，保存后由管理员点「刷新工具」） */
    @PostMapping("/mcp-servers")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:mcp:edit", name = "MCP 服务器编辑", description = "新建 MCP 服务器")
    @OperateLog(value = "新建 AI MCP 服务器", recordPayloads = false)
    public Result<AiAdminDtos.McpServerVo> createServer(
            @Valid @RequestBody AiAdminDtos.McpServerSaveReq req, HttpServletRequest request) {
        return Result.success("创建成功", toVo(mcpServerService.create(req, operatorId(request))));
    }

    /** 修改服务器（编码不可改） */
    @PutMapping("/mcp-servers/{id}")
    @RequiresSuper
    @RequiresPermission(value = "admin:ai:mcp:edit", name = "MCP 服务器编辑", description = "修改 MCP 服务器")
    @OperateLog(value = "修改 AI MCP 服务器", recordPayloads = false)
    public Result<AiAdminDtos.McpServerVo> updateServer(
            @PathVariable Long id, @Valid @RequestBody AiAdminDtos.McpServerSaveReq req, HttpServletRequest request) {
        return Result.success("保存成功", toVo(mcpServerService.update(id, req, operatorId(request))));
    }

    /** 删除服务器（其下工具置停用，不物理删除） */
    @DeleteMapping("/mcp-servers/{id}")
    @RequiresPermission(value = "admin:ai:mcp:edit", name = "MCP 服务器编辑", description = "删除 MCP 服务器")
    @OperateLog("删除 AI MCP 服务器")
    public Result<Void> deleteServer(@PathVariable Long id, HttpServletRequest request) {
        mcpServerService.delete(id, operatorId(request));
        return Result.success("删除成功", null);
    }

    /**
     * 刷新工具清单。
     *
     * <p>远端不可达时返回失败并记录 last_error，不会影响已发现的工具（它们保持原状态）。</p>
     */
    @PostMapping("/mcp-servers/{id}/refresh")
    @RequiresPermission(value = "admin:ai:mcp:edit", name = "MCP 服务器编辑", description = "刷新 MCP 工具清单")
    @OperateLog("刷新 AI MCP 工具清单")
    public Result<AiAdminDtos.McpServerVo> refresh(@PathVariable Long id, HttpServletRequest request) {
        AiMcpServerEntity entity = mcpServerService.refresh(id, operatorId(request));
        return Result.success(
                "已同步 " + (entity.getToolCount() == null ? 0 : entity.getToolCount()) + " 个工具", toVo(entity));
    }

    private AiAdminDtos.McpServerVo toVo(AiMcpServerEntity entity) {
        int enabled = 0;
        List<AiToolEntity> tools = mcpServerService.toolsOfServer(entity.getId());
        for (AiToolEntity tool : tools) {
            if ("ENABLED".equalsIgnoreCase(tool.getStatus())) {
                enabled++;
            }
        }
        return new AiAdminDtos.McpServerVo(
                entity.getId(),
                entity.getServerCode(),
                entity.getName(),
                entity.getUrl(),
                AiMcpHeaderSecurity.maskForDisplay(entity.getHeadersJson()),
                entity.getReadOnly(),
                entity.getStatus(),
                entity.getToolCount(),
                enabled,
                entity.getLastRefreshTime(),
                entity.getLastError(),
                entity.getCreateTime());
    }
}
