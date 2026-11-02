package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiMcpServerEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiMcpServerMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.tool.declarative.McpClient;
import com.astral.ai.tool.declarative.McpToolExecutor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MCP 服务器管理：增删改查 + 工具发现。
 *
 * <p>「刷新」会连到远端 MCP 端点、握手、拉 {@code tools/list}，把每个远端工具落成一行
 * {@code ai_tool}（executor_type=MCP）。风险策略在发现阶段就定死：</p>
 * <ul>
 *   <li>服务器声明只读（{@code read_only=true}）且远端没有否定 {@code readOnlyHint} → READ + NONE；</li>
 *   <li>其余情况（服务器非只读、或远端工具显式声明有副作用）→ HIGH + ADMIN_CONFIRM，
 *       运行期还会被 {@link AiToolService} 的「HIGH 必人工确认」再拦一次。</li>
 * </ul>
 * <p>远端已下线的工具置 DISABLED 而不物理删除，避免历史对话里的工具引用变成悬空。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMcpServerService {

    private static final Pattern SERVER_CODE = Pattern.compile("[a-z][a-z0-9_]{2,63}");

    private final AiMcpServerMapper serverMapper;
    private final AiToolMapper toolMapper;
    private final AiProperties properties;

    // ==================== 查询 ====================

    /** 服务器列表 */
    public List<AiMcpServerEntity> list() {
        return serverMapper.selectList(new LambdaQueryWrapper<AiMcpServerEntity>()
                .orderByDesc(AiMcpServerEntity::getCreateTime));
    }

    /** 按主键取服务器，不存在报错 */
    public AiMcpServerEntity require(Long id) {
        AiMcpServerEntity entity = id == null ? null : serverMapper.selectById(id);
        if (entity == null) {
            throw new AiException("AI008", "MCP 服务器不存在: " + id);
        }
        return entity;
    }

    // ==================== 增删改 ====================

    /** 新建服务器（不自动刷新，由调用方决定是否立刻发现工具） */
    @Transactional(rollbackFor = Exception.class)
    public AiMcpServerEntity create(AiAdminDtos.McpServerSaveReq req, Long operatorId) {
        String code = normalizeCode(req.serverCode());
        Long exists = serverMapper.selectCount(new LambdaQueryWrapper<AiMcpServerEntity>()
                .eq(AiMcpServerEntity::getServerCode, code));
        if (exists != null && exists > 0) {
            throw new AiException("AI008", "MCP 服务器编码已存在: " + code);
        }
        // 地址在这里就校验一次，避免把明显非法的配置存进库
        McpClient.requireEndpoint(req.url(), allowedHosts());
        AiMcpServerEntity entity = new AiMcpServerEntity();
        entity.setServerCode(code);
        entity.setName(req.name());
        entity.setUrl(req.url().trim());
        entity.setHeadersJson(blankToNull(req.headers()));
        entity.setReadOnly(req.readOnly() == null || req.readOnly());
        entity.setStatus("ENABLED");
        entity.setToolCount(0);
        entity.setCreatedBy(operatorId);
        entity.setUpdatedBy(operatorId);
        serverMapper.insert(entity);
        log.info("[AI] 新建 MCP 服务器 {}（{}）", code, entity.getUrl());
        return entity;
    }

    /** 修改服务器（编码不可改，避免既有工具编码失效） */
    @Transactional(rollbackFor = Exception.class)
    public AiMcpServerEntity update(Long id, AiAdminDtos.McpServerSaveReq req, Long operatorId) {
        AiMcpServerEntity entity = require(id);
        if (req.url() != null && !req.url().isBlank()) {
            McpClient.requireEndpoint(req.url(), allowedHosts());
            entity.setUrl(req.url().trim());
        }
        if (req.name() != null && !req.name().isBlank()) {
            entity.setName(req.name());
        }
        if (req.headers() != null) {
            entity.setHeadersJson(blankToNull(req.headers()));
        }
        if (req.readOnly() != null) {
            entity.setReadOnly(req.readOnly());
        }
        if (req.status() != null && !req.status().isBlank()) {
            String status = req.status().trim().toUpperCase(Locale.ROOT);
            if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
                throw new AiException("AI008", "状态只能是 ENABLED 或 DISABLED");
            }
            entity.setStatus(status);
        }
        entity.setUpdatedBy(operatorId);
        serverMapper.updateById(entity);
        log.info("[AI] 修改 MCP 服务器 {}", entity.getServerCode());
        return entity;
    }

    /** 删除服务器：所发现的工具一并置 DISABLED（不物理删，保留历史引用） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, Long operatorId) {
        AiMcpServerEntity entity = require(id);
        for (AiToolEntity tool : toolsOfServer(id)) {
            tool.setStatus("DISABLED");
            tool.setUpdatedBy(operatorId);
            toolMapper.updateById(tool);
        }
        serverMapper.deleteById(id);
        log.info("[AI] 删除 MCP 服务器 {}，其下 {} 个工具已置为停用", entity.getServerCode(), countOfServer(id));
    }

    // ==================== 工具发现 ====================

    /**
     * 连接远端并同步工具清单。
     *
     * @param id         服务器
     * @param operatorId 操作人
     * @return 刷新后的服务器记录（含工具数与失败原因）
     */
    @Transactional(rollbackFor = Exception.class)
    public AiMcpServerEntity refresh(Long id, Long operatorId) {
        AiMcpServerEntity server = require(id);
        if (!"ENABLED".equalsIgnoreCase(server.getStatus())) {
            throw new AiException("AI008", "服务器已禁用，不能刷新: " + server.getServerCode());
        }
        AiProperties.HttpTool config = properties.getHttpTool();
        int discovered;
        try (McpClient client = McpClient.connect(server.getUrl(), server.getHeadersJson(),
                config.getAllowedHosts(), config.getSecrets(), config.getMcpTimeoutSeconds())) {
            List<McpClient.McpToolInfo> tools = client.listTools();
            discovered = syncTools(server, tools, operatorId);
            server.setToolCount(discovered);
            server.setLastError(null);
        } catch (AiException e) {
            server.setLastError(AiJson.truncate(e.getMessage(), 500));
            server.setLastRefreshTime(LocalDateTime.now());
            server.setUpdatedBy(operatorId);
            serverMapper.updateById(server);
            throw e;
        }
        server.setLastRefreshTime(LocalDateTime.now());
        server.setUpdatedBy(operatorId);
        serverMapper.updateById(server);
        log.info("[AI] 刷新 MCP 服务器 {}：发现 {} 个工具", server.getServerCode(), discovered);
        return server;
    }

    /** 把一个远端工具清单同步进 ai_tool，返回启用数量 */
    private int syncTools(AiMcpServerEntity server, List<McpClient.McpToolInfo> tools, Long operatorId) {
        List<AiToolEntity> existing = toolsOfServer(server.getId());
        List<AiToolEntity> touched = new ArrayList<>();
        int enabled = 0;
        for (McpClient.McpToolInfo info : tools) {
            String config = configOf(server, info.name(), info.readOnlyHint());
            AiToolEntity entity = findByRemoteName(existing, info.name());
            boolean fresh = entity == null;
            if (fresh) {
                entity = new AiToolEntity();
                entity.setToolCode(McpToolExecutor.toolCodeOf(server.getServerCode(), info.name()));
                entity.setHandlerKey(McpToolExecutor.HANDLER_KEY);
                entity.setExecutorType(AiConstants.ExecutorType.MCP);
                entity.setCreatedBy(operatorId);
            }
            entity.setExecutorConfig(config);
            entity.setName(info.title() == null || info.title().isBlank() ? info.name() : info.title());
            entity.setDescription(info.description());
            entity.setArgumentsSchema(info.inputSchema() == null ? null : AiJson.write(info.inputSchema()));
            boolean readOnly = McpToolExecutor.readOnlyOf(Boolean.TRUE.equals(server.getReadOnly()),
                    info.readOnlyHint());
            entity.setRiskLevel(McpToolExecutor.riskLevelOf(Boolean.TRUE.equals(server.getReadOnly()),
                    info.readOnlyHint()));
            entity.setApprovalPolicy(McpToolExecutor.approvalOf(Boolean.TRUE.equals(server.getReadOnly()),
                    info.readOnlyHint()));
            entity.setSideEffects(readOnly ? "调用 MCP 只读工具" : "调用 MCP 工具（可能产生写操作）");
            if (fresh) {
                entity.setClientTypes(AiJson.joinCsv(AiConstants.ClientType.all()));
                entity.setStatus("ENABLED");
            } else if (!"DISABLED".equalsIgnoreCase(entity.getStatus())) {
                entity.setStatus("ENABLED");
            }
            entity.setUpdatedBy(operatorId);
            if (fresh) {
                toolMapper.insert(entity);
            } else {
                toolMapper.updateById(entity);
            }
            touched.add(entity);
            if ("ENABLED".equalsIgnoreCase(entity.getStatus())) {
                enabled++;
            }
        }
        // 远端已下线：置停用，保留行
        for (AiToolEntity entity : existing) {
            if (touched.stream().noneMatch(item -> item.getId().equals(entity.getId()))) {
                entity.setStatus("DISABLED");
                entity.setUpdatedBy(operatorId);
                toolMapper.updateById(entity);
            }
        }
        return enabled;
    }

    private String configOf(AiMcpServerEntity server, String remoteToolName, Boolean readOnlyHint) {
        var node = AiJson.mapper().createObjectNode();
        node.put("serverId", server.getId());
        node.put("toolName", remoteToolName);
        if (readOnlyHint != null) {
            node.put("readOnlyHint", readOnlyHint);
        }
        return AiJson.write(node);
    }

    private AiToolEntity findByRemoteName(List<AiToolEntity> existing, String remoteToolName) {
        for (AiToolEntity entity : existing) {
            var config = AiJson.readObject(entity.getExecutorConfig());
            if (remoteToolName.equals(AiJson.text(config, "toolName", null))) {
                return entity;
            }
        }
        return null;
    }

    /** 某服务器发现出来的工具（不区分状态） */
    public List<AiToolEntity> toolsOfServer(Long serverId) {
        List<AiToolEntity> all = toolMapper.selectList(new LambdaQueryWrapper<AiToolEntity>()
                .eq(AiToolEntity::getExecutorType, AiConstants.ExecutorType.MCP)
                .orderByAsc(AiToolEntity::getToolCode));
        List<AiToolEntity> result = new ArrayList<>();
        for (AiToolEntity entity : all) {
            if (serverId.equals(McpToolExecutor.serverIdOf(entity))) {
                result.add(entity);
            }
        }
        return result;
    }

    private int countOfServer(Long serverId) {
        return toolsOfServer(serverId).size();
    }

    private List<String> allowedHosts() {
        return properties.getHttpTool().getAllowedHosts();
    }

    private static String normalizeCode(String code) {
        String value = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!SERVER_CODE.matcher(value).matches()) {
            throw new AiException("AI008", "服务器编码只能是小写字母开头的 snake_case（3-64 位）: " + code);
        }
        return value;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
