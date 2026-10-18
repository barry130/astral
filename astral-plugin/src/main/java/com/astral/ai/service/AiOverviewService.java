package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.mapper.AiAgentMapper;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.ai.mapper.AiProviderRouteMapper;
import com.astral.ai.mapper.AiToolMapper;
import com.astral.ai.security.AiSecretCipher;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.OperateLog;
import com.astral.log.service.OperateLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台总览与配置审计。
 *
 * <p>「审计」读取的是宿主统一的操作日志 {@code sys_operate_log}，过滤条件是请求路径命中
 * {@code /api/v1/admin/ai}。AI 模块因此不必自建与宿主并行的审计表——管理员每一次后台写入
 * 本来就已经被 {@code @OperateLog} 记录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiOverviewService {

    /** AI 后台接口路径前缀，审计据此过滤 */
    private static final String ADMIN_PREFIX = "/api/v1/admin/ai";

    private final AiProperties properties;
    private final AiSecretCipher cipher;
    private final AiProviderService providerService;
    private final AiAgentService agentService;
    private final AiPromptService promptService;
    private final AiKnowledgeService knowledgeService;
    private final AiRequestService requestService;
    private final AiUsageService usageService;
    private final AiFeedbackService feedbackService;
    private final AiModelMapper modelMapper;
    private final AiProviderRouteMapper routeMapper;
    private final AiAgentMapper agentMapper;
    private final AiToolMapper toolMapper;
    private final OperateLogService operateLogService;

    /** 首页概览 */
    public AiAdminDtos.OverviewVo overview() {
        List<AiProviderEntity> providers = providerService.list();
        long enabledProviders = providers.stream().filter(p -> Boolean.TRUE.equals(p.getEnabled())).count();

        List<AiAgentEntity> agents = agentMapper.selectList(new LambdaQueryWrapper<AiAgentEntity>()
                .isNull(AiAgentEntity::getDeletedAt)
                .orderByAsc(AiAgentEntity::getSort)
                .orderByAsc(AiAgentEntity::getId));

        // 一次性取回所有已发布版本，避免逐个查询
        List<Long> versionIds = agents.stream()
                .map(AiAgentEntity::getPublishedVersionId)
                .filter(java.util.Objects::nonNull)
                .toList();
        Map<Long, AiAgentVersionEntity> versionMap = versionIds.isEmpty()
                ? new HashMap<>() : agentService.versionsByIds(versionIds);

        List<AiAdminDtos.ProviderHealthVo> health = new ArrayList<>();
        for (AiProviderEntity provider : providers) {
            health.add(new AiAdminDtos.ProviderHealthVo(provider.getId(), provider.getName(),
                    provider.getHealthStatus(), provider.getLastHealthMessage(), provider.getLastHealthTime()));
        }

        List<AiAdminDtos.AgentBriefVo> briefs = new ArrayList<>();
        for (AiAgentEntity agent : agents) {
            AiAgentVersionEntity published = agent.getPublishedVersionId() == null
                    ? null : versionMap.get(agent.getPublishedVersionId());
            briefs.add(new AiAdminDtos.AgentBriefVo(agent.getId(), agent.getAgentCode(), agent.getName(),
                    agent.getAgentType(), agent.getStatus(),
                    published == null ? null : published.getVersionNo(),
                    AiJson.splitCsv(agent.getClientTypes())));
        }

        List<AiToolEntity> tools = toolMapper.selectList(null);
        long[] today = usageService.todayTokens();
        LocalDate todayDate = LocalDate.now();

        return new AiAdminDtos.OverviewVo(
                properties.isEnabled(),
                com.astral.ai.AiPropertiesHolder.isSchemaReady(),
                cipher.isConfigured(),
                providers.size(),
                enabledProviders,
                modelMapper.selectCount(null),
                routeMapper.selectCount(null),
                agents.size(),
                agents.stream().filter(a -> AiConstants.AgentStatus.PUBLISHED.equals(a.getStatus())).count(),
                promptService.listTemplates().size(),
                knowledgeService.listSources().size(),
                tools.size(),
                requestService.countSince(todayDate.atStartOfDay(), null),
                requestService.countRunning(),
                requestService.countSince(todayDate.atStartOfDay(), AiConstants.RequestStatus.FAILED),
                today[0],
                today[1],
                health,
                briefs);
    }

    /**
     * 配置审计，按请求路径前缀读取宿主操作日志。
     *
     * @param pageNum  页码
     * @param pageSize 每页条数
     */
    public List<AiAdminDtos.AuditVo> audit(long pageNum, long pageSize) {
        long size = PageQuery.pageSize(pageSize);
        long num = PageQuery.pageNum(pageNum);
        List<OperateLog> rows = operateLogService.list(new LambdaQueryWrapper<OperateLog>()
                .likeRight(OperateLog::getRequestUrl, ADMIN_PREFIX)
                .orderByDesc(OperateLog::getId)
                .last("limit " + size + " offset " + ((num - 1) * size)));
        List<AiAdminDtos.AuditVo> result = new ArrayList<>();
        for (OperateLog row : rows) {
            result.add(new AiAdminDtos.AuditVo(row.getId(), row.getOperateType(), "API", null,
                    parseId(row.getUserId()), row.getUsername(), buildDetail(row), row.getIp(),
                    row.getCreateTime()));
        }
        return result;
    }

    /** 近 30 天反馈数（可按类型过滤，type 为空表示全部） */
    public long feedbackCount(String type) {
        return feedbackService.countByType(type, LocalDate.now().minusDays(30).atStartOfDay());
    }

    private String buildDetail(OperateLog row) {
        StringBuilder sb = new StringBuilder();
        sb.append(row.getRequestMethod() == null ? "" : row.getRequestMethod()).append(' ')
                .append(row.getRequestUrl() == null ? "" : row.getRequestUrl());
        if (row.getStatus() != null && row.getStatus() == 0) {
            sb.append(" | 失败: ").append(row.getErrorMsg() == null ? "" : row.getErrorMsg());
        }
        return sb.toString();
    }

    private Long parseId(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
