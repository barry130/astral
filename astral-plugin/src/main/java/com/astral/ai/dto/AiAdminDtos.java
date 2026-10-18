package com.astral.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端后台配置 DTO 集合。
 *
 * <p>入参用 record + Jakarta Validation；出参统一 {@code XxxVo}，<b>绝不回显密钥</b>
 * （只回显 {@code credentialSource}：ENCRYPTED / SECRET_REF / NONE）。</p>
 */
public final class AiAdminDtos {

    private AiAdminDtos() {
    }

    // ==================== 提供方 ====================

    /**
     * 提供方保存请求。
     *
     * @param providerCode     编码（创建后不可改）
     * @param name             名称
     * @param providerType     类型（OPENAI_COMPATIBLE）
     * @param baseUrl          接入地址
     * @param apiKey           明文密钥；仅写入，编辑时留空表示不修改
     * @param secretRef        环境变量引用（apiKey 为空时生效）
     * @param enabled          是否启用
     * @param connectTimeoutMs 连接超时
     * @param readTimeoutMs    读取超时
     * @param maxRetries       重试次数
     * @param weight           权重
     * @param remark           备注
     */
    public record ProviderSaveReq(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String providerCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 32) String providerType,
            @NotBlank @Size(max = 512) String baseUrl,
            @Size(max = 512) String apiKey,
            @Size(max = 128) String secretRef,
            Boolean enabled,
            @Min(500) @Max(120000) Integer connectTimeoutMs,
            @Min(1000) @Max(600000) Integer readTimeoutMs,
            @Min(0) @Max(5) Integer maxRetries,
            @Min(1) @Max(10000) Integer weight,
            @Size(max = 512) String remark
    ) {
    }

    /** 提供方视图 */
    public record ProviderVo(
            Long id,
            String providerCode,
            String name,
            String providerType,
            String baseUrl,
            Boolean enabled,
            String secretRef,
            String credentialSource,
            Integer connectTimeoutMs,
            Integer readTimeoutMs,
            Integer maxRetries,
            Integer weight,
            String healthStatus,
            LocalDateTime lastHealthTime,
            String lastHealthMessage,
            String remark,
            Integer modelCount,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /** 连接测试结果 */
    public record ProviderTestVo(boolean healthy, String message, long latencyMs, String healthStatus) {
    }

    // ==================== 模型 ====================

    /**
     * 模型保存请求。
     *
     * @param providerId        所属提供方
     * @param modelCode         模型编码（请求上游用）
     * @param modelName         展示名
     * @param modelKind         类型（CHAT/REASONING/VISION/EMBEDDING/RERANK）
     * @param supportsStream    是否支持流式
     * @param supportsTools     是否支持工具调用
     * @param supportsJson      是否支持 JSON 输出
     * @param supportsVision    是否支持图片
     * @param maxContextTokens  上下文上限
     * @param maxOutputTokens   输出上限
     * @param inputPrice        输入单价
     * @param outputPrice       输出单价
     * @param currency          币种
     * @param enabled           是否启用
     * @param remark            备注
     */
    public record ModelSaveReq(
            @NotNull Long providerId,
            @NotBlank @Size(max = 128) String modelCode,
            @NotBlank @Size(max = 128) String modelName,
            @Size(max = 32) String modelKind,
            Boolean supportsStream,
            Boolean supportsTools,
            Boolean supportsJson,
            Boolean supportsVision,
            @Min(1) @Max(4000000) Integer maxContextTokens,
            @Min(1) @Max(1000000) Integer maxOutputTokens,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            @Size(max = 8) String currency,
            Boolean enabled,
            @Size(max = 512) String remark
    ) {
    }

    /** 模型视图 */
    public record ModelVo(
            Long id,
            Long providerId,
            String providerName,
            String modelCode,
            String modelName,
            String modelKind,
            Boolean supportsStream,
            Boolean supportsTools,
            Boolean supportsJson,
            Boolean supportsVision,
            Integer maxContextTokens,
            Integer maxOutputTokens,
            BigDecimal inputPrice,
            BigDecimal outputPrice,
            String currency,
            Boolean enabled,
            String remark,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    // ==================== 路由 ====================

    /**
     * 路由保存请求。
     *
     * @param routeCode          路由编码
     * @param name               名称
     * @param providerId         主提供方
     * @param modelId            主模型
     * @param fallbackModelId    兜底模型
     * @param temperature        温度
     * @param topP               top_p
     * @param maxTokens          输出上限
     * @param extraParametersJson 厂商扩展参数（JSON）
     * @param enabled            是否启用
     * @param remark             备注
     */
    public record RouteSaveReq(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String routeCode,
            @NotBlank @Size(max = 128) String name,
            @NotNull Long providerId,
            @NotNull Long modelId,
            Long fallbackModelId,
            BigDecimal temperature,
            BigDecimal topP,
            @Min(1) @Max(1000000) Integer maxTokens,
            String extraParametersJson,
            Boolean enabled,
            @Size(max = 512) String remark
    ) {
    }

    /** 路由视图 */
    public record RouteVo(
            Long id,
            String routeCode,
            String name,
            Long providerId,
            String providerName,
            Long modelId,
            String modelCode,
            Long fallbackModelId,
            String fallbackModelCode,
            BigDecimal temperature,
            BigDecimal topP,
            Integer maxTokens,
            String extraParametersJson,
            Boolean enabled,
            String remark,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    // ==================== 智能体 ====================

    /**
     * 智能体基础信息保存请求。
     *
     * @param agentCode           编码（创建后不可改）
     * @param name                名称
     * @param agentType           类型
     * @param description         描述
     * @param icon                图标
     * @param clientTypes         可用客户端
     * @param entryConfig         入口配置（JSON）
     * @param permissionCode      通用使用权限编码
     * @param clientPermissionCodes 各客户端权限编码（JSON）
     * @param quotaPolicyId       额度策略
     * @param allowUserContext    是否允许用户上下文
     * @param allowFileContext    是否允许文件上下文
     * @param conversationEnabled 是否允许多轮会话
     * @param feedbackEnabled     是否允许反馈
     * @param sort                排序
     */
    public record AgentSaveReq(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String agentCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 32) String agentType,
            @Size(max = 512) String description,
            @Size(max = 64) String icon,
            List<String> clientTypes,
            String entryConfig,
            @Size(max = 96) String permissionCode,
            String clientPermissionCodes,
            Long quotaPolicyId,
            Boolean allowUserContext,
            Boolean allowFileContext,
            Boolean conversationEnabled,
            Boolean feedbackEnabled,
            Integer sort
    ) {
    }

    /** 智能体视图 */
    public record AgentVo(
            Long id,
            String agentCode,
            String name,
            String agentType,
            String description,
            String icon,
            String status,
            List<String> clientTypes,
            String entryConfig,
            String permissionCode,
            String clientPermissionCodes,
            Long quotaPolicyId,
            Long publishedVersionId,
            Integer publishedVersionNo,
            Boolean allowUserContext,
            Boolean allowFileContext,
            Boolean conversationEnabled,
            Boolean feedbackEnabled,
            Integer sort,
            Integer versionCount,
            Integer draftVersionCount,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /**
     * 智能体版本保存请求（仅 DRAFT 可改）。
     *
     * @param versionName            版本名
     * @param providerRouteId        模型路由
     * @param systemPromptVersionId  系统提示词版本
     * @param contextPolicyJson      上下文策略
     * @param responseSchemaJson     响应结构
     * @param toolPolicyJson         工具策略
     * @param knowledgePolicyJson    知识策略
     * @param modelParametersJson    模型参数
     * @param safetyPolicyJson       安全策略
     * @param quotaOverrideJson      额度覆盖
     * @param toolIds                绑定工具 ID 列表（含单个工具的覆盖项另走工具绑定接口）
     */
    public record AgentVersionSaveReq(
            @Size(max = 128) String versionName,
            Long providerRouteId,
            Long systemPromptVersionId,
            String contextPolicyJson,
            String responseSchemaJson,
            String toolPolicyJson,
            String knowledgePolicyJson,
            String modelParametersJson,
            String safetyPolicyJson,
            String quotaOverrideJson,
            List<Long> toolIds
    ) {
    }

    /** 智能体版本视图 */
    public record AgentVersionVo(
            Long id,
            Long agentId,
            Integer versionNo,
            String versionName,
            String status,
            Long providerRouteId,
            String routeName,
            Long systemPromptVersionId,
            String promptName,
            String clientTypesSnapshot,
            String entryConfigSnapshot,
            String contextPolicyJson,
            String responseSchemaJson,
            String toolPolicyJson,
            String knowledgePolicyJson,
            String modelParametersJson,
            String safetyPolicyJson,
            String quotaOverrideJson,
            String validationSummaryJson,
            Long publishedBy,
            LocalDateTime publishedAt,
            Long rollbackFromVersionId,
            List<Long> toolIds,
            List<Long> knowledgeSourceIds,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /** 版本校验结果 */
    public record ValidationVo(boolean valid, List<ValidationIssue> issues) {

        /** 单条校验问题 */
        public record ValidationIssue(String code, String level, String message, String field) {
        }
    }

    // ==================== 提示词 ====================

    /**
     * 提示词模板保存请求。
     *
     * @param promptCode  编码
     * @param name        名称
     * @param scene       场景
     * @param description 描述
     */
    public record PromptTemplateSaveReq(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String promptCode,
            @NotBlank @Size(max = 128) String name,
            @Size(max = 64) String scene,
            @Size(max = 512) String description
    ) {
    }

    /**
     * 提示词版本保存请求。
     *
     * @param systemPrompt       系统提示词
     * @param userPromptTemplate 用户提示词模板
     * @param variablesJson      变量声明
     * @param changelog          变更说明
     */
    public record PromptVersionSaveReq(
            String systemPrompt,
            String userPromptTemplate,
            String variablesJson,
            @Size(max = 512) String changelog
    ) {
    }

    /** 提示词模板视图 */
    public record PromptTemplateVo(
            Long id,
            String promptCode,
            String name,
            String scene,
            String description,
            String status,
            Long publishedVersionId,
            Integer publishedVersionNo,
            Integer versionCount,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /** 提示词版本视图 */
    public record PromptVersionVo(
            Long id,
            Long templateId,
            Integer versionNo,
            String status,
            String systemPrompt,
            String userPromptTemplate,
            String variablesJson,
            String changelog,
            Long publishedBy,
            LocalDateTime publishedAt,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    // ==================== 知识库 ====================

    /**
     * 知识源保存请求。
     *
     * @param sourceCode   编码
     * @param name         名称
     * @param sourceType   来源类型（PROJECT_DOCUMENT / API_DOCUMENT / ...）
     * @param description  描述
     * @param pathPattern  路径/匹配规则
     * @param enabled      是否启用
     * @param syncMode     同步方式（MANUAL/AUTO）
     */
    public record KnowledgeSourceSaveReq(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String sourceCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 48) String sourceType,
            @Size(max = 512) String description,
            @Size(max = 512) String pathPattern,
            Boolean enabled,
            @Size(max = 16) String syncMode
    ) {
    }

    /**
     * 知识文档保存请求。
     *
     * @param docKey    文档稳定标识（同源内唯一）
     * @param title     标题
     * @param path      路径
     * @param section   章节
     * @param version   文档版本
     * @param content   正文
     */
    public record KnowledgeDocumentSaveReq(
            @NotBlank @Size(max = 256) String docKey,
            @NotBlank @Size(max = 256) String title,
            @Size(max = 512) String path,
            @Size(max = 256) String section,
            @Size(max = 64) String version,
            @NotBlank String content
    ) {
    }

    /** 知识源视图 */
    public record KnowledgeSourceVo(
            Long id,
            String sourceCode,
            String name,
            String sourceType,
            String description,
            String pathPattern,
            Boolean enabled,
            String syncMode,
            String status,
            LocalDateTime lastSyncTime,
            Integer documentCount,
            Integer chunkCount,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /** 知识文档视图 */
    public record KnowledgeDocumentVo(
            Long id,
            Long sourceId,
            String docKey,
            String title,
            String path,
            String section,
            String version,
            String contentHash,
            String status,
            Integer chunkCount,
            LocalDateTime indexedAt,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /** 索引任务视图 */
    public record IndexJobVo(
            Long id,
            Long sourceId,
            String jobType,
            String status,
            Integer totalCount,
            Integer processedCount,
            Integer failedCount,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            String errorSummary
    ) {
    }

    /**
     * 智能体版本知识绑定请求。
     *
     * @param sourceId        知识源
     * @param enabled         是否启用
     * @param priority        优先级
     * @param retrievalTopK   召回条数
     * @param minScore        最低相关度
     * @param citationPolicy  引用策略（REQUIRED / OPTIONAL / NONE）
     */
    public record AgentKnowledgeSaveReq(
            @NotNull Long sourceId,
            Boolean enabled,
            Integer priority,
            @Min(1) @Max(50) Integer retrievalTopK,
            BigDecimal minScore,
            @Size(max = 32) String citationPolicy
    ) {
    }

    /** 智能体知识绑定视图 */
    public record AgentKnowledgeVo(
            Long id,
            Long agentVersionId,
            Long sourceId,
            String sourceName,
            String sourceType,
            Boolean enabled,
            Integer priority,
            Integer retrievalTopK,
            BigDecimal minScore,
            String citationPolicy
    ) {
    }

    // ==================== 工具 ====================

    /**
     * 工具编辑请求（只允许改风险等级、确认策略、启停与描述；handler_key 不可改）。
     *
     * @param name           名称
     * @param description    描述
     * @param riskLevel      风险等级（只能提高不能降低代码下限，服务端强制）
     * @param approvalPolicy 确认策略
     * @param status         状态
     */
    public record ToolSaveReq(
            @Size(max = 128) String name,
            @Size(max = 512) String description,
            @Size(max = 16) String riskLevel,
            @Size(max = 24) String approvalPolicy,
            @Size(max = 16) String status
    ) {
    }

    /** 工具视图 */
    public record ToolVo(
            Long id,
            String toolCode,
            String name,
            String description,
            String handlerKey,
            List<String> clientTypes,
            List<String> permissionCodes,
            String riskLevel,
            String approvalPolicy,
            String argumentsSchema,
            String resultSchema,
            String sideEffects,
            String status,
            Boolean handlerAvailable,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    /**
     * 智能体工具绑定请求。
     *
     * @param toolId                  工具
     * @param enabled                 是否启用
     * @param sort                    排序
     * @param riskOverride            风险覆盖（只能提高）
     * @param approvalPolicy          确认策略覆盖
     * @param argumentsSchemaOverride 参数结构覆盖
     */
    public record AgentToolSaveReq(
            @NotNull Long toolId,
            Boolean enabled,
            Integer sort,
            @Size(max = 16) String riskOverride,
            @Size(max = 24) String approvalPolicy,
            String argumentsSchemaOverride
    ) {
    }

    /** 智能体工具绑定视图 */
    public record AgentToolVo(
            Long id,
            Long agentVersionId,
            Long toolId,
            String toolCode,
            String toolName,
            String riskLevel,
            String effectiveRiskLevel,
            String approvalPolicy,
            Boolean enabled,
            Integer sort
    ) {
    }

    /**
     * 工具调用审批请求。
     *
     * @param approved 是否通过
     * @param comment  审批意见
     */
    public record ToolApproveReq(@NotNull Boolean approved, @Size(max = 512) String comment) {
    }

    // ==================== 额度 ====================

    /**
     * 额度策略保存请求。
     *
     * @param policyCode          编码
     * @param name                名称
     * @param scopeType           作用域（GLOBAL/AGENT/ROLE/USER）
     * @param agentId             智能体
     * @param roleId              角色
     * @param userId              用户
     * @param dailyRequests       日请求数
     * @param monthlyRequests     月请求数
     * @param dailyInputTokens    日输入 Token
     * @param monthlyInputTokens  月输入 Token
     * @param dailyOutputTokens   日输出 Token
     * @param monthlyOutputTokens 月输出 Token
     * @param maxInputChars       单次输入字符
     * @param maxContextTokens    上下文 Token
     * @param maxOutputTokens     输出 Token
     * @param maxConcurrent       并发数
     * @param monthlyCostLimit    月度费用上限
     * @param enabled             是否启用
     * @param remark              备注
     */
    public record QuotaPolicySaveReq(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9_-]+$") String policyCode,
            @NotBlank @Size(max = 128) String name,
            @NotBlank @Size(max = 16) String scopeType,
            Long agentId,
            Long roleId,
            Long userId,
            @Min(0) Integer dailyRequests,
            @Min(0) Integer monthlyRequests,
            @Min(0) Long dailyInputTokens,
            @Min(0) Long monthlyInputTokens,
            @Min(0) Long dailyOutputTokens,
            @Min(0) Long monthlyOutputTokens,
            @Min(0) Integer maxInputChars,
            @Min(0) Integer maxContextTokens,
            @Min(0) Integer maxOutputTokens,
            @Min(0) Integer maxConcurrent,
            BigDecimal monthlyCostLimit,
            Boolean enabled,
            @Size(max = 512) String remark
    ) {
    }

    /** 额度策略视图 */
    public record QuotaPolicyVo(
            Long id,
            String policyCode,
            String name,
            String scopeType,
            Long agentId,
            Long roleId,
            Long userId,
            Integer dailyRequests,
            Integer monthlyRequests,
            Long dailyInputTokens,
            Long monthlyInputTokens,
            Long dailyOutputTokens,
            Long monthlyOutputTokens,
            Integer maxInputChars,
            Integer maxContextTokens,
            Integer maxOutputTokens,
            Integer maxConcurrent,
            BigDecimal monthlyCostLimit,
            Boolean enabled,
            String remark,
            LocalDateTime createTime,
            LocalDateTime updateTime
    ) {
    }

    // ==================== 用量 / 请求 / 审计 ====================

    /** 用量视图 */
    public record UsageVo(
            Long id,
            Long requestId,
            Long userId,
            Long agentId,
            String agentName,
            Long providerId,
            Long modelId,
            String modelCode,
            Integer inputTokens,
            Integer outputTokens,
            BigDecimal estimatedCost,
            String currency,
            String status,
            LocalDateTime createTime
    ) {
    }

    /** 用量汇总 */
    public record UsageSummaryVo(
            long requestCount,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCost,
            String currency,
            List<UsageTrendPoint> trend
    ) {
    }

    /** 用量趋势点 */
    public record UsageTrendPoint(String date, long requestCount, long inputTokens, long outputTokens) {
    }

    /** 请求列表视图（不含正文，避免敏感内容泄露） */
    public record RequestVo(
            Long id,
            Long conversationId,
            Long agentId,
            String agentName,
            Long agentVersionId,
            Integer agentVersionNo,
            Long userId,
            String clientType,
            String status,
            String errorCode,
            String errorSummary,
            Integer tokenIn,
            Integer tokenOut,
            Long durationMs,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            LocalDateTime createTime
    ) {
    }

    /** 请求详情（正文需 conversation:content:view 权限） */
    public record RequestDetailVo(
            RequestVo request,
            String inputSnapshot,
            String contextSnapshot,
            String configurationSnapshot,
            String knowledgeSnapshot,
            String toolPolicySnapshot,
            String safetyPolicySnapshot,
            List<ToolCallVo> toolCalls,
            List<EventVo> events,
            List<MessageVo> messages
    ) {
    }

    /** 工具调用视图 */
    public record ToolCallVo(
            Long id,
            Long requestId,
            String toolCode,
            String toolName,
            String riskLevel,
            String status,
            String argumentsJson,
            String resultJson,
            String errorMessage,
            String approvalStatus,
            Long approvedBy,
            LocalDateTime approvedAt,
            LocalDateTime startedAt,
            LocalDateTime finishedAt
    ) {
    }

    /** 请求事件视图 */
    public record EventVo(
            Long sequence,
            String type,
            String dataJson,
            LocalDateTime createdAt
    ) {
    }

    /** 消息视图（管理端审计用） */
    public record MessageVo(
            Long id,
            Long conversationId,
            Long requestId,
            String role,
            String content,
            String contentType,
            String status,
            String blocksJson,
            String citationsJson,
            String modelName,
            Integer inputTokens,
            Integer outputTokens,
            LocalDateTime createTime
    ) {
    }

    /** 审计记录视图 */
    public record AuditVo(
            Long id,
            String action,
            String targetType,
            String targetId,
            Long operatorId,
            String operatorName,
            String detail,
            String clientIp,
            LocalDateTime createTime
    ) {
    }

    /** 配置总览 */
    public record OverviewVo(
            boolean pluginEnabled,
            boolean schemaReady,
            boolean secretKeyConfigured,
            long providerCount,
            long enabledProviderCount,
            long modelCount,
            long routeCount,
            long agentCount,
            long publishedAgentCount,
            long promptCount,
            long knowledgeSourceCount,
            long toolCount,
            long todayRequestCount,
            long runningRequestCount,
            long failedRequestCount,
            long todayInputTokens,
            long todayOutputTokens,
            List<ProviderHealthVo> providerHealth,
            List<AgentBriefVo> agents
    ) {
    }

    /** 提供方健康摘要 */
    public record ProviderHealthVo(Long id, String name, String healthStatus, String healthMessage,
                                   LocalDateTime lastHealthTime) {
    }

    /** 智能体摘要 */
    public record AgentBriefVo(Long id, String agentCode, String name, String agentType, String status,
                               Integer publishedVersionNo, List<String> clientTypes) {
    }
}
