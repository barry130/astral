package com.astral.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

import cn.dev33.satoken.stp.StpInterface;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.security.AiToolPermissionGate;
import com.astral.ai.security.AiToolPermissionGate.Outcome;
import com.astral.ai.service.AiOverviewService;
import com.astral.ai.service.AiProviderService;
import com.astral.ai.service.AiRequestService;
import com.astral.ai.service.AiUsageService;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.auth.security.PermissionChecker;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.log.service.LoginLogService;
import com.astral.log.service.OperateLogService;
import com.astral.plugin.api.PluginRegistry;
import com.astral.system.mail.MailService;
import com.astral.system.notify.NotifyPublisher;
import com.astral.system.service.RoleService;
import com.astral.system.service.SysConfigService;
import com.astral.system.service.UserRoleService;
import com.astral.system.service.UserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R10 管理助手工具权限码契约。
 *
 * <p>期望值锚定审计契约原文（docs/audit/2026-11-07/backend-ai-read-recovery.md R10）：
 * AdminToolProvider 全部 22 个工具的 permissionCodes 为空，导致 invoke 级权限校验对
 * {@code admin.*} 工具完全失效——只持有 {@code admin:ai:config:view} 的管理员就能让助手
 * 读日志/反馈/存储并在审批后改配置、改角色、发公告、发信。契约要求逐工具给出具体资源码
 * （全部取自已存在的管理端权限码），{@code change_role_permission} 用 {@code *:*:*}
 * （仅超管，等价于 HTTP 面的 @RequiresSuper），空码只允许出现在纯数学/日期类工具——
 * 本工具集没有这类工具。</p>
 */
class AdminToolProviderPermissionCodeTest {

    private static final Long LOG_VIEWER = 41L;
    private static final Long STRANGER = 42L;
    private static final Long USER_EDITOR = 43L;

    /** 审计 R10 的逐工具资源码（全部是代码库已存在的管理端权限码）。 */
    private static final Map<String, List<String>> EXPECTED = Map.ofEntries(
            Map.entry("get_system_health", List.of("admin:monitor:view")),
            Map.entry("get_plugin_status", List.of("admin:plugin:view")),
            Map.entry("get_user_summary", List.of("admin:system:user:view")),
            Map.entry("get_permission_summary", List.of("admin:system:permission:view")),
            Map.entry("get_storage_summary", List.of("admin:storage:view")),
            Map.entry("search_operate_logs", List.of("admin:log:view")),
            Map.entry("search_login_logs", List.of("admin:log:view")),
            Map.entry("search_feedback", List.of("admin:feedback:view")),
            Map.entry("get_usage_statistics", List.of("admin:ai:usage:view")),
            Map.entry("check_config_completeness", List.of("admin:ai:config:view")),
            Map.entry("draft_notice", List.of("admin:message:edit")),
            Map.entry("draft_email", List.of("admin:system:mail:view")),
            Map.entry("draft_feedback_reply", List.of("admin:feedback:view")),
            Map.entry("draft_config_change", List.of("admin:system:config:view")),
            Map.entry("draft_plugin_operation", List.of("admin:plugin:view")),
            Map.entry("disable_provider", List.of("admin:ai:provider:edit")),
            Map.entry("update_config", List.of("admin:system:config:edit")),
            Map.entry("change_user_status", List.of("admin:system:user:edit")),
            Map.entry("change_role_permission", List.of("*:*:*")),
            Map.entry("delete_file", List.of("admin:storage:edit")),
            Map.entry("publish_notice", List.of("admin:message:edit")),
            Map.entry("send_email", List.of("admin:system:notify:rule:edit")));

    @Test
    void everyToolDeclaresItsAuditedResourcePermissionCodes() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (var handler : provider().handlers()) {
            actual.put(handler.definition().toolCode(), handler.definition().permissionCodes());
        }
        assertEquals(EXPECTED, actual, "admin assistant tools must carry per-tool resource codes (audit R10)");
    }

    @Test
    void noToolCarriesEmptyCodes() {
        for (var handler : provider().handlers()) {
            assertFalse(
                    handler.definition().permissionCodes().isEmpty(),
                    handler.definition().toolCode() + " declares no permission codes; empty codes are only"
                            + " allowed for pure math/date tools, which this provider does not ship");
        }
    }

    @Test
    void logViewerPassesOperateLogSearchWhileStrangersAreDenied() {
        MutablePermissionSource permissions = new MutablePermissionSource();
        permissions.grants.put(LOG_VIEWER, List.of("admin:log:view"));
        AiToolPermissionGate gate = new AiToolPermissionGate(new PermissionChecker(permissions));
        var handler = handlerOf("search_operate_logs");
        AiToolEntity noStoredCodes = new AiToolEntity();

        assertEquals(Outcome.ALLOWED, gate.check(LOG_VIEWER, noStoredCodes, handler));
        assertEquals(Outcome.DENIED, gate.check(STRANGER, noStoredCodes, handler));
    }

    @Test
    void roleReassignmentAdmitsOnlySuperusers() {
        MutablePermissionSource permissions = new MutablePermissionSource();
        permissions.grants.put(USER_EDITOR, List.of("admin:system:user:edit"));
        permissions.grants.put(STRANGER, List.of("*:*:*"));
        AiToolPermissionGate gate = new AiToolPermissionGate(new PermissionChecker(permissions));
        var handler = handlerOf("change_role_permission");
        AiToolEntity noStoredCodes = new AiToolEntity();

        assertEquals(
                Outcome.DENIED,
                gate.check(USER_EDITOR, noStoredCodes, handler),
                "user editors must not reassign roles through the assistant");
        assertEquals(Outcome.ALLOWED, gate.check(STRANGER, noStoredCodes, handler));
    }

    private static AdminToolProvider provider() {
        return new AdminToolProvider(
                mock(UserService.class),
                mock(RoleService.class),
                mock(UserRoleService.class),
                mock(SysConfigService.class),
                mock(OperateLogService.class),
                mock(LoginLogService.class),
                mock(PluginRegistry.class),
                mock(AiProviderService.class),
                mock(AiRequestService.class),
                mock(AiUsageService.class),
                mock(AiOverviewService.class),
                mock(SysNoticeMapper.class),
                mock(MailService.class),
                mock(NotifyPublisher.class),
                mock(JdbcTemplate.class),
                mock(ObjectProvider.class));
    }

    private static AiToolHandler handlerOf(String toolCode) {
        return provider().handlers().stream()
                .filter(handler -> toolCode.equals(handler.definition().toolCode()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("tool not registered: " + toolCode));
    }

    private static final class MutablePermissionSource implements StpInterface {
        private final Map<Object, List<String>> grants = new ConcurrentHashMap<>();
        private final List<Object> checkedUsers = new CopyOnWriteArrayList<>();

        @Override
        public List<String> getPermissionList(Object loginId, String loginType) {
            checkedUsers.add(loginId);
            return grants.getOrDefault(loginId, List.of());
        }

        @Override
        public List<String> getRoleList(Object loginId, String loginType) {
            return List.of();
        }
    }
}
