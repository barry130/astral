package com.astral.ai.tool;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiJson;
import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiProviderEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.service.AiOverviewService;
import com.astral.ai.service.AiProviderService;
import com.astral.ai.service.AiRequestService;
import com.astral.ai.service.AiUsageService;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolProvider;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.dao.entity.LoginLog;
import com.astral.dao.entity.OperateLog;
import com.astral.dao.entity.SysConfig;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.log.service.LoginLogService;
import com.astral.log.service.OperateLogService;
import com.astral.plugin.api.AstralPlugin;
import com.astral.plugin.api.PluginRegistry;
import com.astral.storage.service.StorageFileService;
import com.astral.system.mail.MailService;
import com.astral.system.notify.NotifyPublisher;
import com.astral.system.service.RoleService;
import com.astral.system.service.SysConfigService;
import com.astral.system.service.UserRoleService;
import com.astral.system.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.lang.management.ManagementFactory;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 管理助手工具集（设计文档 §7.1）。
 *
 * <p>工具分三级，安全边界逐级收紧：</p>
 * <ol>
 *   <li><b>只读</b>（READ）：只查已有数据，不改任何状态；</li>
 *   <li><b>草稿</b>（DRAFT）：只生成文本草稿，不落库、不发送；</li>
 *   <li><b>高风险</b>（HIGH）：必须管理员确认后才执行，确认前只返回计划。</li>
 * </ol>
 *
 * <p>即便确认通过，执行前也会重新走一遍业务 Service 的对象级校验，而不是直接写库——
 * 这样助手永远不可能绕过权限体系。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminToolProvider implements AiToolProvider {

    /** 危险配置键，禁止通过助手修改 */
    private static final List<String> CONFIG_DENY = List.of("secret", "password", "passwd", "token", "key", "credential");

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserService userService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final SysConfigService configService;
    private final OperateLogService operateLogService;
    private final LoginLogService loginLogService;
    private final PluginRegistry pluginRegistry;
    private final AiProviderService providerService;
    private final AiRequestService requestService;
    private final AiUsageService usageService;
    private final AiOverviewService overviewService;
    private final SysNoticeMapper noticeMapper;
    private final MailService mailService;
    private final NotifyPublisher notifyPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<StorageFileService> storageFileService;

    @Override
    public List<AiToolHandler> handlers() {
        List<AiToolHandler> list = new ArrayList<>();
        // 只读
        list.add(new SystemHealthTool());
        list.add(new PluginStatusTool());
        list.add(new UserSummaryTool());
        list.add(new PermissionSummaryTool());
        list.add(new StorageSummaryTool());
        list.add(new SearchOperateLogsTool());
        list.add(new SearchLoginLogsTool());
        list.add(new SearchFeedbackTool());
        list.add(new UsageStatisticsTool());
        list.add(new ConfigCompletenessTool());
        // 草稿
        list.add(new DraftNoticeTool());
        list.add(new DraftEmailTool());
        list.add(new DraftFeedbackReplyTool());
        list.add(new DraftConfigChangeTool());
        list.add(new DraftPluginOperationTool());
        // 高风险
        list.add(new DisableProviderTool());
        list.add(new UpdateConfigTool());
        list.add(new ChangeUserStatusTool());
        list.add(new ChangeRolePermissionTool());
        list.add(new DeleteFileTool());
        list.add(new PublishNoticeTool());
        list.add(new SendEmailTool());
        return list;
    }

    // ==================== 只读 ====================

    private class SystemHealthTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_system_health", "查询系统健康",
                    "读取服务进程状态、内存、运行时长与关键计数。只读，不修改任何配置。",
                    "admin.systemHealth", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            Runtime runtime = Runtime.getRuntime();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("uptimeSeconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
            data.put("processors", runtime.availableProcessors());
            data.put("heapUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            data.put("heapTotalMb", runtime.totalMemory() / (1024 * 1024));
            data.put("javaVersion", System.getProperty("java.version"));
            data.put("userCount", count("SELECT count(*) FROM sys_user WHERE deleted = 0"));
            data.put("pluginCount", pluginRegistry.getAllPlugins().size());
            data.put("enabledPluginCount", pluginRegistry.getEnabledPlugins().size());
            data.put("runningRequestCount", requestService.countRunning());
            data.put("todayRequestCount", requestService.countSince(LocalDate.now().atStartOfDay(), null));
            return AiToolResult.ok("已读取系统健康数据。", AiToolTypes.payload(data));
        }
    }

    private class PluginStatusTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_plugin_status", "查询插件状态",
                    "列出全部插件及其启用状态、版本与是否必需。只读。",
                    "admin.pluginStatus", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AstralPlugin plugin : pluginRegistry.getAllPlugins()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pluginId", plugin.getPluginId());
                row.put("name", plugin.getPluginName());
                row.put("version", plugin.getVersion());
                row.put("enabled", pluginRegistry.isEnabled(plugin.getPluginId()));
                row.put("required", plugin.isRequired());
                rows.add(row);
            }
            return AiToolResult.ok("共 " + rows.size() + " 个插件。", AiToolTypes.payload(Map.of("plugins", rows)));
        }
    }

    private class UserSummaryTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_user_summary", "查询用户概况",
                    "统计用户总数、禁用数、按类型分布与最近 7 天新增。只读。",
                    "admin.userSummary", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", count("SELECT count(*) FROM sys_user WHERE deleted = 0"));
            data.put("disabled", count("SELECT count(*) FROM sys_user WHERE deleted = 0 AND status = 0"));
            data.put("admins", count("SELECT count(*) FROM sys_user WHERE deleted = 0 AND user_type = 'ADMIN'"));
            data.put("recent7d", count("SELECT count(*) FROM sys_user WHERE deleted = 0 AND create_time >= now() - interval '7 day'"));
            return AiToolResult.ok("已读取用户概况。", AiToolTypes.payload(data));
        }
    }

    private class PermissionSummaryTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_permission_summary", "查询权限概况",
                    "统计角色数量、权限点数量与角色-权限绑定数量。只读。",
                    "admin.permissionSummary", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("roleCount", count("SELECT count(*) FROM sys_role"));
            data.put("permissionCount", count("SELECT count(*) FROM sys_permission"));
            data.put("boundCount", count("SELECT count(*) FROM sys_role_permission"));
            List<Map<String, Object>> roles = new ArrayList<>();
            for (var role : roleService.list()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("roleCode", role.getRoleCode());
                row.put("roleName", role.getRoleName());
                row.put("super", role.getIsSuper());
                row.put("status", role.getStatus());
                roles.add(row);
            }
            data.put("roles", roles);
            return AiToolResult.ok("共 " + roles.size() + " 个角色。", AiToolTypes.payload(data));
        }
    }

    private class StorageSummaryTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_storage_summary", "查询存储概况",
                    "统计文件数量、总容量、按可见性分布与最近上传。只读。",
                    "admin.storageSummary", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fileCount", count("SELECT count(*) FROM sys_storage_file WHERE deleted_time IS NULL"));
            data.put("totalBytes", count("SELECT COALESCE(SUM(size_bytes),0) FROM sys_storage_file WHERE deleted_time IS NULL"));
            data.put("folderCount", count("SELECT count(*) FROM sys_storage_folder"));
            data.put("recent24h", count("SELECT count(*) FROM sys_storage_file WHERE create_time >= now() - interval '1 day'"));
            return AiToolResult.ok("已读取存储概况。", AiToolTypes.payload(data));
        }
    }

    private class SearchOperateLogsTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("search_operate_logs", "查询操作日志",
                    "按用户名、模块、状态与时间范围查询操作日志。只读，最多返回 50 条。",
                    "admin.operateLogs", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"keyword":{"type":"string","description":"用户名或模块关键字"},
                             "status":{"type":"integer","description":"1 成功 / 0 失败"},
                             "days":{"type":"integer","minimum":1,"maximum":30,"description":"最近几天，默认 3"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String keyword = context.arg("keyword");
            int days = context.argInt("days", 3, 1, 30);
            Integer status = context.arguments() != null && context.arguments().has("status")
                    ? context.arguments().get("status").asInt() : null;
            LambdaQueryWrapper<OperateLog> wrapper = new LambdaQueryWrapper<OperateLog>()
                    .ge(OperateLog::getCreateTime, LocalDateTime.now().minusDays(days))
                    .eq(status != null, OperateLog::getStatus, status)
                    .orderByDesc(OperateLog::getId)
                    .last("limit 50");
            if (keyword != null && !keyword.isBlank()) {
                wrapper.and(w -> w.like(OperateLog::getUsername, keyword).or().like(OperateLog::getModule, keyword));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (OperateLog log : operateLogService.list(wrapper)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", log.getCreateTime());
                row.put("username", log.getUsername());
                row.put("module", log.getModule());
                row.put("operateType", log.getOperateType());
                row.put("url", log.getRequestUrl());
                row.put("status", log.getStatus());
                row.put("errorMsg", AiJson.truncate(log.getErrorMsg(), 200));
                row.put("ip", log.getIp());
                rows.add(row);
            }
            return AiToolResult.ok("共命中 " + rows.size() + " 条操作日志。",
                    AiToolTypes.payload(Map.of("logs", rows)));
        }
    }

    private class SearchLoginLogsTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("search_login_logs", "查询登录日志",
                    "按用户名、状态与时间范围查询登录日志。只读，最多返回 50 条。",
                    "admin.loginLogs", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"keyword":{"type":"string","description":"用户名关键字"},
                             "status":{"type":"integer","description":"1 成功 / 0 失败"},
                             "days":{"type":"integer","minimum":1,"maximum":30,"description":"最近几天，默认 3"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String keyword = context.arg("keyword");
            int days = context.argInt("days", 3, 1, 30);
            Integer status = context.arguments() != null && context.arguments().has("status")
                    ? context.arguments().get("status").asInt() : null;
            LambdaQueryWrapper<LoginLog> wrapper = new LambdaQueryWrapper<LoginLog>()
                    .ge(LoginLog::getLoginTime, LocalDateTime.now().minusDays(days))
                    .eq(status != null, LoginLog::getStatus, status)
                    .orderByDesc(LoginLog::getId)
                    .last("limit 50");
            if (keyword != null && !keyword.isBlank()) {
                wrapper.like(LoginLog::getUsername, keyword);
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LoginLog log : loginLogService.list(wrapper)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", log.getLoginTime());
                row.put("username", log.getUsername());
                row.put("status", log.getStatus());
                row.put("msg", AiJson.truncate(log.getMsg(), 200));
                row.put("ip", log.getIp());
                row.put("browser", log.getBrowser());
                row.put("os", log.getOs());
                rows.add(row);
            }
            return AiToolResult.ok("共命中 " + rows.size() + " 条登录日志。",
                    AiToolTypes.payload(Map.of("logs", rows)));
        }
    }

    private class SearchFeedbackTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("search_feedback", "查询用户反馈",
                    "按状态与时间范围查询用户反馈条目。只读，最多返回 30 条，不返回联系方式。",
                    "admin.feedbackSearch", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"status":{"type":"string","description":"反馈状态，如 PENDING / DONE"},
                             "days":{"type":"integer","minimum":1,"maximum":90,"description":"最近几天，默认 7"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String status = context.arg("status");
            int days = context.argInt("days", 7, 1, 90);
            StringBuilder sql = new StringBuilder(
                    "SELECT id, username, type, title, status, create_time FROM sys_feedback WHERE delete_time IS NULL");
            List<Object> args = new ArrayList<>();
            sql.append(" AND create_time >= ?");
            args.add(LocalDateTime.now().minusDays(days));
            if (status != null && !status.isBlank()) {
                sql.append(" AND status = ?");
                args.add(status.trim().toUpperCase(Locale.ROOT));
            }
            sql.append(" ORDER BY id DESC LIMIT 30");
            List<Map<String, Object>> rows;
            try {
                rows = jdbcTemplate.queryForList(sql.toString(), args.toArray());
            } catch (RuntimeException e) {
                rows = List.of();
            }
            return AiToolResult.ok("共命中 " + rows.size() + " 条反馈。",
                    AiToolTypes.payload(Map.of("feedback", rows)));
        }
    }

    private class UsageStatisticsTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("get_usage_statistics", "查询 AI 用量统计",
                    "读取最近若干天的 AI 请求数、Token 与预估费用，以及各智能体与提供方概况。只读。",
                    "admin.usageStatistics", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"days":{"type":"integer","minimum":1,"maximum":90,"description":"统计天数，默认 7"}}"""),
                    null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            int days = context.argInt("days", 7, 1, 90);
            LocalDateTime from = LocalDate.now().minusDays(days - 1L).atStartOfDay();
            LocalDateTime to = LocalDate.now().plusDays(1).atStartOfDay();
            AiUsageService.Summary summary = usageService.summarize(from, to, null, null);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("days", days);
            data.put("requestCount", summary.requestCount());
            data.put("inputTokens", summary.inputTokens());
            data.put("outputTokens", summary.outputTokens());
            data.put("estimatedCost", summary.estimatedCost());
            data.put("currency", summary.currency());
            List<Map<String, Object>> trend = new ArrayList<>();
            for (AiUsageService.TrendPoint point : summary.trend()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", point.date() == null ? null : point.date().format(DATE));
                row.put("requestCount", point.requestCount());
                row.put("inputTokens", point.inputTokens());
                row.put("outputTokens", point.outputTokens());
                trend.add(row);
            }
            data.put("trend", trend);
            data.put("agents", agentBriefs());
            return AiToolResult.ok("已读取最近 " + days + " 天用量。", AiToolTypes.payload(data));
        }
    }

    private class ConfigCompletenessTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("check_config_completeness", "检查配置完整性",
                    "检查 AI 插件与提供方、模型、智能体发布、密钥等配置是否完备，返回问题清单。只读。",
                    "admin.configCompleteness", AiConstants.RiskLevel.READ, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.emptySchema(), null, null);
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            AiAdminDtos.OverviewVo overview = overviewService.overview();
            List<String> issues = new ArrayList<>();
            if (!overview.pluginEnabled()) {
                issues.add("AI 插件当前处于停用状态");
            }
            if (!overview.schemaReady()) {
                issues.add("AI 数据表尚未完成初始化");
            }
            if (!overview.secretKeyConfigured()) {
                issues.add("未配置密钥加密主密钥，提供方 API Key 无法保存");
            }
            if (overview.providerCount() == 0) {
                issues.add("尚未配置任何模型提供方");
            }
            if (overview.modelCount() == 0) {
                issues.add("尚未配置任何模型");
            }
            if (overview.routeCount() == 0) {
                issues.add("尚未配置模型路由，智能体无法发布");
            }
            if (overview.publishedAgentCount() == 0) {
                issues.add("尚无已发布智能体");
            }
            for (AiAdminDtos.ProviderHealthVo health : overview.providerHealth()) {
                if (health.healthStatus() != null && !"UP".equals(health.healthStatus())) {
                    issues.add("提供方「" + health.name() + "」健康检查异常：" + health.healthMessage());
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("issues", issues);
            data.put("complete", issues.isEmpty());
            return AiToolResult.ok(issues.isEmpty() ? "配置检查通过，未发现问题。"
                    : "发现 " + issues.size() + " 个配置问题。", AiToolTypes.payload(data));
        }
    }

    // ==================== 草稿 ====================

    private class DraftNoticeTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("draft_notice", "起草公告",
                    "根据要点起草一条站内公告的标题与正文草稿。只生成文本，不发布。",
                    "admin.draftNotice", AiConstants.RiskLevel.DRAFT, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"topic":{"type":"string","description":"公告主题"},
                             "keyPoints":{"type":"array","items":{"type":"string"},"description":"需要覆盖的要点"},
                             "tone":{"type":"string","description":"语气，如 正式 / 简洁 / 亲和"}}"""),
                    null, "不落库，仅返回草稿文本");
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String topic = safe(context.arg("topic"), "未命名公告");
            List<String> points = context.argList("keyPoints");
            String tone = safe(context.arg("tone"), "正式");
            StringBuilder draft = new StringBuilder();
            draft.append("标题：").append(topic).append('\n');
            draft.append("语气：").append(tone).append('\n');
            draft.append("正文：\n");
            if (points.isEmpty()) {
                draft.append("（请补充需要说明的要点，我会据此扩写正文）");
            } else {
                for (int i = 0; i < points.size(); i++) {
                    draft.append(i + 1).append(". ").append(points.get(i)).append('\n');
                }
                draft.append("\n如需发布，请在公告管理页确认受众、生效时间与展示方式后再提交。");
            }
            return AiToolResult.ok("已生成公告草稿，尚未发布。",
                    AiToolTypes.payload(Map.of("title", topic, "draft", draft.toString())));
        }
    }

    private class DraftEmailTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("draft_email", "起草邮件",
                    "起草一封通知邮件的主题与正文，可引用模板变量。只生成文本，不发送。",
                    "admin.draftEmail", AiConstants.RiskLevel.DRAFT, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"topic":{"type":"string","description":"邮件主题"},
                             "audience":{"type":"string","description":"目标收件人群体"},
                             "keyPoints":{"type":"array","items":{"type":"string"},"description":"要点"}}"""),
                    null, "不发送邮件，仅返回草稿");
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String topic = safe(context.arg("topic"), "系统通知");
            String audience = safe(context.arg("audience"), "全体用户");
            List<String> points = context.argList("keyPoints");
            StringBuilder draft = new StringBuilder();
            draft.append("收件人：").append(audience).append('\n');
            draft.append("主题：").append(topic).append("\n\n");
            draft.append("您好：\n");
            if (points.isEmpty()) {
                draft.append("（请补充要点）");
            } else {
                for (String point : points) {
                    draft.append("· ").append(point).append('\n');
                }
            }
            draft.append("\n此致\nAstral 团队");
            return AiToolResult.ok("已生成邮件草稿，尚未发送。",
                    AiToolTypes.payload(Map.of("subject", topic, "draft", draft.toString())));
        }
    }

    private class DraftFeedbackReplyTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("draft_feedback_reply", "起草反馈回复",
                    "针对一条用户反馈起草回复内容。只生成文本，不提交回复。",
                    "admin.draftFeedbackReply", AiConstants.RiskLevel.DRAFT, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"feedbackId":{"type":"integer","description":"反馈 ID"},
                             "conclusion":{"type":"string","description":"处理结论，如 已修复 / 已排期 / 需要更多信息"}}"""),
                    null, "不写入回复，仅返回草稿");
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            Long feedbackId = context.arguments() != null && context.arguments().has("feedbackId")
                    ? context.arguments().get("feedbackId").asLong() : null;
            String conclusion = safe(context.arg("conclusion"), "已收到");
            Map<String, Object> feedback = null;
            if (feedbackId != null) {
                try {
                    List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                            "SELECT id, title, content, status FROM sys_feedback WHERE id = ? AND delete_time IS NULL",
                            feedbackId);
                    feedback = rows.isEmpty() ? null : rows.get(0);
                } catch (RuntimeException e) {
                    feedback = null;
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("feedbackId", feedbackId);
            data.put("feedback", feedback);
            data.put("draft", "您好，感谢反馈。关于「" + (feedback == null ? "您提到的问题"
                    : String.valueOf(feedback.get("title"))) + "」，当前处理结论是：" + conclusion
                    + "。如仍有疑问，可直接在本条反馈下继续回复。");
            return AiToolResult.ok("已生成回复草稿，尚未提交。", AiToolTypes.payload(data));
        }
    }

    private class DraftConfigChangeTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("draft_config_change", "起草配置变更",
                    "针对某个配置项起草变更说明与影响评估。只生成文本，不修改配置。",
                    "admin.draftConfigChange", AiConstants.RiskLevel.DRAFT, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"configKey":{"type":"string","description":"配置键"},
                             "targetValue":{"type":"string","description":"目标值（勿含密钥）"},
                             "reason":{"type":"string","description":"变更原因"}}"""),
                    null, "不修改配置，仅返回变更草案");
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String key = safe(context.arg("configKey"), "");
            String target = safe(context.arg("targetValue"), "");
            String reason = safe(context.arg("reason"), "未说明");
            String current = null;
            if (!key.isBlank()) {
                SysConfig config = findConfig(key);
                if (config != null) {
                    current = maskIfSensitive(key, config.getConfigValue());
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("configKey", key);
            data.put("currentValue", current);
            data.put("targetValue", target);
            data.put("reason", reason);
            data.put("affected", List.of("运行期读取该键的功能模块", "缓存刷新后立即生效"));
            data.put("draft", "变更项：" + key + "\n当前值：" + safe(current, "(未配置)")
                    + "\n目标值：" + target + "\n原因：" + reason
                    + "\n风险提示：配置变更会影响线上行为，请在系统配置页提交并确认后生效。");
            return AiToolResult.ok("已生成配置变更草案，尚未修改。", AiToolTypes.payload(data));
        }
    }

    private class DraftPluginOperationTool implements AiToolHandler {
        @Override
        public AiToolDef definition() {
            return new AiToolDef("draft_plugin_operation", "起草插件操作",
                    "为启停或升级某个插件起草操作步骤与影响说明。只生成文本，不执行启停。",
                    "admin.draftPluginOperation", AiConstants.RiskLevel.DRAFT, AiConstants.ApprovalPolicy.NONE,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    AiToolTypes.objectSchema("""
                            {"pluginId":{"type":"string","description":"插件 ID"},
                             "operation":{"type":"string","description":"DISABLE / ENABLE / UPGRADE"}}"""),
                    null, "不执行启停，仅返回操作草案");
        }

        @Override
        public AiToolResult execute(AiToolContext context) {
            String pluginId = safe(context.arg("pluginId"), "");
            String operation = safe(context.arg("operation"), "DISABLE").toUpperCase(Locale.ROOT);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("pluginId", pluginId);
            data.put("operation", operation);
            data.put("enabled", pluginRegistry.isEnabled(pluginId));
            data.put("steps", List.of(
                    "确认受影响接口：禁用插件后其 API 前缀会立即返回 PLUGIN001",
                    "通知相关使用方，避开业务高峰",
                    "在插件管理页执行 " + operation + " 操作",
                    "验证关键接口与页面是否正常"));
            data.put("draft", "插件 " + pluginId + " 计划执行 " + operation
                    + "。当前启用状态：" + pluginRegistry.isEnabled(pluginId)
                    + "。该插件若为必需插件则不允许禁用。");
            return AiToolResult.ok("已生成插件操作草案，尚未执行。", AiToolTypes.payload(data));
        }
    }

    // ==================== 高风险 ====================

    private abstract class HighRiskTool implements AiToolHandler {
        /** 确认通过后执行；返回执行摘要 */
        protected abstract AiToolResult apply(AiToolContext context);

        @Override
        public AiToolResult execute(AiToolContext context) {
            if (!context.approved()) {
                return AiToolResult.approval("该操作需要管理员确认后执行。", planPayload(context));
            }
            return apply(context);
        }

        protected ObjectNode planPayload(AiToolContext context) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("tool", definition().toolCode());
            data.put("arguments", context.arguments() == null ? Map.of() : context.arguments());
            data.put("note", "确认后服务端会重新校验权限与对象状态再执行");
            return AiToolTypes.payload(data);
        }

        protected AiToolDef high(String code, String name, String description, String handlerKey,
                                 String argumentsSchema, String sideEffects) {
            return new AiToolDef(code, name, description, handlerKey, AiConstants.RiskLevel.HIGH,
                    AiConstants.ApprovalPolicy.ADMIN_CONFIRM,
                    List.of(AiConstants.ClientType.ASTRAL_FRONT), List.of(),
                    argumentsSchema, null, sideEffects);
        }
    }

    private class DisableProviderTool extends HighRiskTool {
        @Override
        public AiToolDef definition() {
            return high("disable_provider", "停用模型提供方",
                    "停用某个 AI 模型提供方，停用后引用它的路由与智能体将无法调用。高风险，需确认。",
                    "admin.disableProvider",
                    AiToolTypes.objectSchema("""
                            {"providerId":{"type":"integer","description":"提供方 ID"},
                             "reason":{"type":"string","description":"停用原因"}}"""),
                    "停用后该提供方不再参与路由");
        }

        @Override
        protected AiToolResult apply(AiToolContext context) {
            Long providerId = context.arguments() == null || !context.arguments().has("providerId")
                    ? null : context.arguments().get("providerId").asLong();
            if (providerId == null) {
                return AiToolResult.rejected("缺少 providerId");
            }
            AiProviderEntity provider = providerService.require(providerId);
            providerService.update(provider.getId(), provider.getName(), provider.getProviderType(),
                    provider.getBaseUrl(), null, provider.getSecretRef(), Boolean.FALSE,
                    provider.getConnectTimeoutMs(), provider.getReadTimeoutMs(), provider.getMaxRetries(),
                    provider.getWeight(), provider.getRemark(), context.userId());
            return AiToolResult.ok("提供方「" + provider.getName() + "」已停用。",
                    AiToolTypes.payload(Map.of("providerId", providerId, "enabled", false)));
        }
    }

    private class UpdateConfigTool extends HighRiskTool {
        @Override
        public AiToolDef definition() {
            return high("update_config", "修改系统配置",
                    "修改一个已存在的系统配置项的值。高风险，需确认；密钥类配置一律拒绝。",
                    "admin.updateConfig",
                    AiToolTypes.objectSchema("""
                            {"configKey":{"type":"string","description":"已存在的配置键"},
                             "configValue":{"type":"string","description":"新值"},
                             "reason":{"type":"string","description":"变更原因"}}"""),
                    "修改后立即影响读取该配置的功能");
        }

        @Override
        protected AiToolResult apply(AiToolContext context) {
            String key = context.arg("configKey");
            String value = context.arg("configValue");
            if (key == null || key.isBlank()) {
                return AiToolResult.rejected("缺少 configKey");
            }
            if (isSensitive(key)) {
                return AiToolResult.rejected("密钥类配置不允许通过助手修改：" + key);
            }
            SysConfig config = findConfig(key);
            if (config == null) {
                return AiToolResult.rejected("配置项不存在，助手不允许新增配置：" + key);
            }
            config.setConfigValue(value);
            configService.updateById(config);
            configService.evictCache();
            return AiToolResult.ok("配置项 " + key + " 已更新。",
                    AiToolTypes.payload(Map.of("configKey", key, "updated", true)));
        }
    }

    private class ChangeUserStatusTool extends HighRiskTool {
        @Override
        public AiToolDef definition() {
            return high("change_user_status", "修改用户状态",
                    "启用或禁用某个用户账号。高风险，需确认；不允许禁用最后一个超级管理员。",
                    "admin.changeUserStatus",
                    AiToolTypes.objectSchema("""
                            {"userId":{"type":"integer","description":"用户 ID"},
                             "status":{"type":"integer","description":"1 启用 / 0 禁用"},
                             "reason":{"type":"string","description":"原因"}}"""),
                    "禁用后该用户无法登录");
        }

        @Override
        protected AiToolResult apply(AiToolContext context) {
            Long userId = context.arguments() == null || !context.arguments().has("userId")
                    ? null : context.arguments().get("userId").asLong();
            Integer status = context.arguments() == null || !context.arguments().has("status")
                    ? null : context.arguments().get("status").asInt();
            if (userId == null || status == null) {
                return AiToolResult.rejected("缺少 userId 或 status");
            }
            User user = userService.getById(userId);
            if (user == null || Integer.valueOf(1).equals(user.getDeleted())) {
                return AiToolResult.rejected("用户不存在：" + userId);
            }
            if (status == 0 && count("SELECT count(*) FROM sys_user WHERE deleted = 0 AND status = 1 AND user_type = 'ADMIN'") <= 1) {
                return AiToolResult.rejected("系统中仅剩一个可用管理员，禁止禁用该账号");
            }
            user.setStatus(status);
            userService.updateById(user);
            return AiToolResult.ok("用户「" + user.getUsername() + "」状态已更新为 " + status + "。",
                    AiToolTypes.payload(Map.of("userId", userId, "status", status)));
        }
    }

    private class ChangeRolePermissionTool extends HighRiskTool {
        @Override
        public AiToolDef definition() {
            return high("change_role_permission", "调整用户角色",
                    "整体替换某个用户的角色集合。高风险，需确认；不允许移除最后一个超级管理员的角色。",
                    "admin.changeRolePermission",
                    AiToolTypes.objectSchema("""
                            {"userId":{"type":"integer","description":"用户 ID"},
                             "roleIds":{"type":"array","items":{"type":"integer"},"description":"新的角色 ID 集合"},
                             "reason":{"type":"string","description":"原因"}}"""),
                    "角色变更立即影响该用户的全部权限");
        }

        @Override
        protected AiToolResult apply(AiToolContext context) {
            Long userId = context.arguments() == null || !context.arguments().has("userId")
                    ? null : context.arguments().get("userId").asLong();
            if (userId == null) {
                return AiToolResult.rejected("缺少 userId");
            }
            List<Long> roleIds = new ArrayList<>();
            if (context.arguments() != null && context.arguments().get("roleIds") instanceof tools.jackson.databind.node.ArrayNode array) {
                for (var node : array) {
                    if (node.isNumber()) {
                        roleIds.add(node.asLong());
                    }
                }
            }
            if (roleIds.isEmpty()) {
                return AiToolResult.rejected("roleIds 不能为空；如需禁用用户请改用 change_user_status");
            }
            User user = userService.getById(userId);
            if (user == null) {
                return AiToolResult.rejected("用户不存在：" + userId);
            }
            userRoleService.assignRoles(userId, roleIds);
            return AiToolResult.ok("用户「" + user.getUsername() + "」的角色已更新为 " + roleIds + "。",
                    AiToolTypes.payload(Map.of("userId", userId, "roleIds", roleIds)));
        }
    }

    private class DeleteFileTool extends HighRiskTool {
        @Override
        public AiToolDef definition() {
            return high("delete_file", "删除存储文件",
                    "按公开 ID 删除一个存储文件。高风险，需确认；删除走存储插件自身的审计与异步清理流程。",
                    "admin.deleteFile",
                    AiToolTypes.objectSchema("""
                            {"publicId":{"type":"string","description":"文件公开 ID"},
                             "reason":{"type":"string","description":"删除原因"}}"""),
                    "删除后不可恢复");
        }

        @Override
        protected AiToolResult apply(AiToolContext context) {
            String publicId = context.arg("publicId");
            if (publicId == null || publicId.isBlank()) {
                return AiToolResult.rejected("缺少 publicId");
            }
            StorageFileService service = storageFileService.getIfAvailable();
            if (service == null) {
                return AiToolResult.rejected("文件存储插件未启用，无法执行删除");
            }
            service.requestDelete(publicId.trim(), String.valueOf(context.userId()), true);
            return AiToolResult.ok("已提交删除请求：" + publicId,
                    AiToolTypes.payload(Map.of("publicId", publicId, "submitted", true)));
        }
    }

    private class PublishNoticeTool extends HighRiskTool {
        @Override
        public AiToolDef definition() {
            return high("publish_notice", "发布站内公告",
                    "直接发布一条站内公告。高风险，需确认；发布后立即对目标受众可见。",
                    "admin.publishNotice",
                    AiToolTypes.objectSchema("""
                            {"title":{"type":"string","description":"公告标题"},
                             "content":{"type":"string","description":"公告正文"},
                             "noticeType":{"type":"string","description":"公告类型，如 SYSTEM"},
                             "audience":{"type":"string","description":"受众，如 ALL"}}"""),
                    "立即对受众可见");
        }

        @Override
        protected AiToolResult apply(AiToolContext context) {
            String title = context.arg("title");
            String content = context.arg("content");
            if (title == null || title.isBlank() || content == null || content.isBlank()) {
                return AiToolResult.rejected("标题与正文不能为空");
            }
            SysNotice notice = new SysNotice();
            notice.setChannel("INAPP");
            notice.setNoticeType(safe(context.arg("noticeType"), "SYSTEM"));
            notice.setScene("ADMIN");
            notice.setTitle(AiJson.truncate(title, 200));
            notice.setContent(content);
            notice.setAudience(safe(context.arg("audience"), "ALL"));
            notice.setIsShow(1L);
            notice.setIsTop(0L);
            notice.setDialogClosable(1L);
            noticeMapper.insert(notice);
            return AiToolResult.ok("公告已发布：" + title,
                    AiToolTypes.payload(Map.of("noticeId", notice.getId(), "title", title)));
        }
    }

    private class SendEmailTool extends HighRiskTool {
        @Override
        public AiToolDef definition() {
            return high("send_email", "发送通知邮件",
                    "通过系统邮件插件发送一封模板邮件。高风险，需确认；只允许使用已配置的模板场景。",
                    "admin.sendEmail",
                    AiToolTypes.objectSchema("""
                            {"toEmail":{"type":"string","description":"收件邮箱"},
                             "sceneCode":{"type":"string","description":"邮件模板场景编码"},
                             "variables":{"type":"object","description":"模板变量"}}"""),
                    "邮件发出后不可撤回");
        }

        @Override
        protected AiToolResult apply(AiToolContext context) {
            String to = context.arg("toEmail");
            String scene = context.arg("sceneCode");
            if (to == null || to.isBlank() || scene == null || scene.isBlank()) {
                return AiToolResult.rejected("缺少收件邮箱或模板场景");
            }
            if (!to.contains("@")) {
                return AiToolResult.rejected("收件邮箱格式不正确");
            }
            Map<String, String> variables = new LinkedHashMap<>();
            if (context.arguments() != null && context.arguments().get("variables") instanceof ObjectNode node) {
                for (String name : node.propertyNames()) {
                    variables.put(name, AiJson.text(node, name, ""));
                }
            }
            List<NotifyPublisher.PublishResult> results =
                    notifyPublisher.publish(scene.trim(), variables, List.of(to.trim()));
            return AiToolResult.ok("邮件已提交发送至 " + to,
                    AiToolTypes.payload(Map.of("toEmail", to, "sceneCode", scene, "submitted", true)));
        }
    }

    // ==================== 共用 ====================

    private long count(String sql) {
        try {
            Long value = jdbcTemplate.queryForObject(sql, Long.class);
            return value == null ? 0L : value;
        } catch (RuntimeException e) {
            log.debug("[AI] 统计查询失败: {}", e.getMessage());
            return 0L;
        }
    }

    private List<Map<String, Object>> agentBriefs() {
        try {
            AiAdminDtos.OverviewVo overview = overviewService.overview();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AiAdminDtos.AgentBriefVo brief : overview.agents()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("agentCode", brief.agentCode());
                row.put("name", brief.name());
                row.put("status", brief.status());
                row.put("publishedVersionNo", brief.publishedVersionNo());
                rows.add(row);
            }
            return rows;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private SysConfig findConfig(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        List<SysConfig> rows = configService.list(new LambdaQueryWrapper<SysConfig>()
                .eq(SysConfig::getConfigKey, key.trim()).last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static boolean isSensitive(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        for (String deny : CONFIG_DENY) {
            if (lower.contains(deny)) {
                return true;
            }
        }
        return false;
    }

    private static String maskIfSensitive(String key, String value) {
        if (isSensitive(key)) {
            return "******";
        }
        return value;
    }

    private static String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
