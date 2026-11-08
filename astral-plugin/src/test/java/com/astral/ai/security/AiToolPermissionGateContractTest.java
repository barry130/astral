package com.astral.ai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.dev33.satoken.stp.StpInterface;
import com.astral.ai.common.AiConstants;
import com.astral.ai.entity.AiToolEntity;
import com.astral.ai.security.AiToolPermissionGate.Outcome;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolDef;
import com.astral.ai.tool.AiToolTypes.AiToolHandler;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.auth.security.PermissionChecker;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * R4 权限咽喉契约：真实权限编码形态（短码/通配/超管码）不得把工具变成全员不可用的死工具，
 * 且存储码与注册码同集异序时不多跑一次冗余权限查询。
 *
 * <p>期望值锚定审计契约原文（docs/audit/2026-11-07/backend-ai-read-recovery.md R4）：
 * 注册码里的 3 段码与 {@code *:*:*} 在旧解析器下抛 AI008 → MALFORMED，属功能性 DoS；
 * 修复后持有者可执行、超管码仅超管可执行。使用真实 {@link PermissionChecker}
 * （含超管通配与层级通配匹配），仅以 StpInterface 替身供给持有权限。</p>
 */
class AiToolPermissionGateContractTest {

    private static final Long HOLDER = 41L;
    private static final Long NON_SUPER = 42L;

    private final MutablePermissionSource permissions = new MutablePermissionSource();
    private final AiToolPermissionGate gate = new AiToolPermissionGate(new PermissionChecker(permissions));

    @Test
    void shortRegisteredCodesStayExecutableForTheirHolders() {
        permissions.grants.put(HOLDER, List.of("admin:qt:admin"));
        AiToolEntity tool = tool("admin:qt:admin");

        assertEquals(Outcome.ALLOWED, gate.check(HOLDER, tool, handler("admin:qt:admin")));
        assertEquals(1, permissions.checkedUsers.size());
    }

    @Test
    void superWildcardRequirementAdmitsOnlySuperusers() {
        permissions.grants.put(HOLDER, List.of("*:*:*"));
        permissions.grants.put(NON_SUPER, List.of("admin:qt:admin"));
        AiToolEntity tool = tool("*:*:*");
        AiToolHandler handler = handler("*:*:*");

        assertEquals(Outcome.ALLOWED, gate.check(HOLDER, tool, handler));
        assertEquals(Outcome.DENIED, gate.check(NON_SUPER, tool, handler));
    }

    @Test
    void reorderedStoredCodesDoNotTriggerARedundantPermissionQuery() {
        permissions.grants.put(HOLDER, List.of("admin:qt:admin", "admin:demo:view"));
        AiToolEntity tool = tool("admin:demo:view,admin:qt:admin");

        assertEquals(Outcome.ALLOWED, gate.check(HOLDER, tool, handler("admin:qt:admin", "admin:demo:view")));
        assertEquals(
                1,
                permissions.checkedUsers.size(),
                "stored codes equal to the registered floor (any order) must skip the second query");
    }

    @Test
    void wildcardHeldPermissionsStillSatisfyTheRegisteredFloor() {
        permissions.grants.put(HOLDER, List.of("admin:system:user:*"));
        AiToolEntity tool = tool("admin:system:user:view");

        assertEquals(Outcome.ALLOWED, gate.check(HOLDER, tool, handler("admin:system:user:view")));
    }

    @Test
    void malformedStoredCodesStayFailClosed() {
        permissions.grants.put(HOLDER, List.of("*:*:*"));
        AiToolEntity tool = tool("not a permission code");

        assertEquals(Outcome.MALFORMED, gate.check(HOLDER, tool, handler("admin:qt:admin")));
        assertEquals(0, permissions.checkedUsers.size());
    }

    private static AiToolEntity tool(String permissionCsv) {
        AiToolEntity tool = new AiToolEntity();
        tool.setId(812L);
        tool.setToolCode("audit_gate_tool");
        tool.setPermissionCodes(permissionCsv);
        return tool;
    }

    private static AiToolHandler handler(String... permissionCodes) {
        return new AiToolHandler() {
            @Override
            public AiToolDef definition() {
                return new AiToolDef(
                        "audit_gate_tool",
                        "Gate fixture",
                        "An isolated handler SPI fixture",
                        "audit-gate-handler",
                        AiConstants.RiskLevel.READ,
                        AiConstants.ApprovalPolicy.NONE,
                        List.of(AiConstants.ClientType.ASTRAL_FRONT),
                        List.of(permissionCodes),
                        "{\"type\":\"object\"}",
                        null,
                        null);
            }

            @Override
            public AiToolResult execute(AiToolContext context) {
                return AiToolResult.ok(
                        "executed", com.astral.ai.common.AiJson.mapper().createObjectNode());
            }
        };
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
