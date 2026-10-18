package com.astral.ai.service;

import com.astral.ai.AiPlugin;
import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.auth.security.PermissionChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能体的入口访问控制。
 *
 * <p>三层判断，顺序固定：</p>
 * <ol>
 *   <li>智能体存在、未停用、有已发布版本 —— 否则 {@code AI002}；</li>
 *   <li>当前客户端在 {@code client_types} 内 —— 否则 {@code AI003}；</li>
 *   <li>用户持有 {@code permission_code} 或对应客户端的权限码 —— 否则 {@code AI004}。</li>
 * </ol>
 *
 * <p>{@code client_types} 只是入口过滤，不能替代权限；反过来权限也不能绕过客户端限制。
 * 超管（{@code *:*:*}）在第三步天然通过。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAccessService {

    private final AiAgentService agentService;
    private final PermissionChecker permissionChecker;

    /**
     * 校验并返回可用于当前客户端与当前用户的智能体。
     *
     * @param agentCode  智能体编码
     * @param clientType 客户端类型
     * @param userId     当前用户
     * @return 已通过全部检查的智能体
     */
    public AiAgentEntity requireForClient(String agentCode, String clientType, Long userId) {
        if (agentCode == null || agentCode.isBlank()) {
            throw new AiException("AI002", "(空)");
        }
        AiAgentEntity agent = agentService.requireAvailable(agentCode.trim());
        if (!supportsClient(agent, clientType)) {
            throw new AiException("AI003", agent.getName());
        }
        if (!permitted(agent, clientType, userId)) {
            throw new AiException("AI004", agent.getName());
        }
        return agent;
    }

    /** 不带权限校验的可用性判断（用于列表过滤） */
    public boolean supportsClient(AiAgentEntity agent, String clientType) {
        List<String> clients = AiJson.splitCsv(agent.getClientTypes());
        if (clients.isEmpty()) {
            return false;
        }
        String target = clientType == null ? "" : clientType.trim().toUpperCase(java.util.Locale.ROOT);
        return !target.isEmpty() && clients.contains(target);
    }

    /** 当前用户是否被允许使用该智能体 */
    public boolean permitted(AiAgentEntity agent, String clientType, Long userId) {
        List<String> required = new ArrayList<>();
        String base = agent.getPermissionCode();
        if (base != null && !base.isBlank()) {
            required.add(base.trim());
        }
        String client = clientPermission(agent, clientType);
        if (client != null) {
            required.add(client);
        }
        if (required.isEmpty()) {
            // 未声明权限码时不放开：管理端两个助手默认要求显式权限
            required.add(defaultPermission(agent.getAgentType()));
        }
        return permissionChecker.hasAnyPermissionByLoginId(userId, required.toArray(new String[0]));
    }

    /**
     * 要求当前管理员显式持有某个权限码。
     *
     * <p>与 {@link #permitted} 的区别：{@code permitted} 是「智能体配置里声明的权限」，
     * 而这里是管理端页面级的第二次校验（例如答疑必须持有 {@code admin:ai:qa:use}）。
     * 智能体配置可以被后台改，页面级能力不应该只依赖它。</p>
     */
    public void requireExplicit(String permissionCode) {
        if (permissionCode == null || permissionCode.isBlank()) {
            return;
        }
        permissionChecker.require(permissionCode.trim());
    }

    /** 从 client_permission_codes 中取出当前客户端对应的权限码 */
    public String clientPermission(AiAgentEntity agent, String clientType) {
        String raw = agent.getClientPermissionCodes();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var node = AiJson.read(raw);
        if (node == null || !node.isObject()) {
            return null;
        }
        String key = clientType == null ? "" : clientType.trim().toUpperCase(java.util.Locale.ROOT);
        String value = AiJson.text(node, key, null);
        return value == null || value.isBlank() ? null : value;
    }

    /** 平台兜底权限：管理助手/答疑必须有显式权限，音乐助手给普通用户默认权限 */
    public String defaultPermission(String agentType) {
        if (AiConstants.AgentType.ADMIN.equals(agentType)) {
            return AiPlugin.Perms.ASSISTANT_USE;
        }
        if (AiConstants.AgentType.QA.equals(agentType)) {
            return AiPlugin.Perms.QA_USE;
        }
        return AiPlugin.PERM_MUSIC_USE;
    }

    /**
     * 解析智能体当前可用的版本。
     * <p>会话绑定的版本只用于「同一会话内保持一致」；一旦该版本被归档（说明后台发布了新版），
     * 就应当切到当前已发布版本，否则用户改完配置发版后，旧会话永远跑旧配置——
     * 这正是「发了版却没效果」的根因。草稿版本绝不参与运行。</p>
     *
     * @param agent           智能体
     * @param conversationVer 会话绑定的版本（可空）
     * @return 实际使用版本
     */
    public AiAgentVersionEntity resolveVersion(AiAgentEntity agent, Long conversationVer) {
        Long publishedId = agent.getPublishedVersionId();
        if (publishedId == null) {
            throw new AiException("AI002", agent.getAgentCode());
        }
        if (conversationVer != null && conversationVer.equals(publishedId)) {
            return agentService.requireVersion(publishedId);
        }
        if (conversationVer != null) {
            AiAgentVersionEntity bound = agentService.requireVersion(conversationVer);
            // 会话绑定的就是当前发布版才沿用；已归档说明后台发了新版，必须跟随；
            // 草稿则完全不参与运行。
            if (AiConstants.VersionStatus.PUBLISHED.equals(bound.getStatus())) {
                return bound;
            }
        }
        return agentService.requireVersion(publishedId);
    }
}
