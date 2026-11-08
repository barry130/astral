package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiQuotaPolicyEntity;
import com.astral.ai.mapper.AiQuotaPolicyMapper;
import com.astral.ai.mapper.AiUserQuotaMapper;
import com.astral.dao.entity.UserRole;
import com.astral.system.service.UserRoleService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 额度解析与预占。
 *
 * <p>核心规则是<b>逐级收紧</b>：全局 → 智能体 → 角色 → 用户，每一级只能在已有上限上取更小值，
 * 版本里的 {@code quotaOverrideJson} 同理，最后还要被进程级硬上限（{@code AiProperties}）兜住。
 * 任何一层配错了都只可能更严格，不可能让用户突破全局策略。</p>
 */
class AiQuotaServiceTest {

    private AiQuotaPolicyMapper policyMapper;
    private AiUserQuotaMapper quotaMapper;
    private AiProperties properties;
    private AiQuotaService service;
    private UserRoleService roles;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test"), AiQuotaPolicyEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "test-role"), UserRole.class);

        policyMapper = mock(AiQuotaPolicyMapper.class);
        quotaMapper = mock(AiUserQuotaMapper.class);
        properties = new AiProperties();
        roles = mock(UserRoleService.class);
        when(roles.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<UserRole>>any()))
                .thenReturn(List.of());
        service = new AiQuotaService(policyMapper, quotaMapper, roles, properties);

        // 默认没有任何策略：所有 selectList 都返回空
        when(policyMapper.selectList(any())).thenReturn(List.of());
    }

    // ==================== 默认与兜底 ====================

    @Test
    void unavailableRolePoliciesCannotSilentlyRelaxQuota() {
        when(roles.list(org.mockito.ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<UserRole>>any()))
                .thenThrow(new IllegalStateException("fixture connection details"));
        var failure = assertThrows(AiException.class, () -> service.resolveLimits(1L, agent(2L, null), version(null)));
        assertEquals("AI005", failure.getErrorCode());
        assertEquals("AI 使用额度不足：无法确认角色额度，请稍后重试", failure.getMessage());
    }

    @Test
    void withoutAnyPolicyTheHardCapsBecomeTheLimits() {
        AiQuotaService.Limits limits = service.resolveLimits(1L, agent(2L, null), version(null));

        assertNull(limits.dailyRequests());
        assertNull(limits.monthlyRequests());
        assertEquals(properties.getHardMaxInputChars(), limits.maxInputChars());
        assertEquals(properties.getHardMaxContextTokens(), limits.maxContextTokens());
        assertEquals(properties.getHardMaxOutputTokens(), limits.maxOutputTokens());
        // 没有任何策略时并发默认 3，而不是「无限」
        assertEquals(3, limits.maxConcurrent());
        assertTrue(limits.sources().isEmpty());
    }

    @Test
    void concurrentDefaultAppliesWhenPolicyLeavesItZero() {
        AiQuotaPolicyEntity policy = policy("global", AiConstants.QuotaScope.GLOBAL);
        policy.setMaxConcurrent(0);
        when(policyMapper.selectList(any())).thenReturn(List.of(policy));

        AiQuotaService.Limits limits = service.resolveLimits(1L, agent(2L, null), version(null));

        assertEquals(3, limits.maxConcurrent());
    }

    // ==================== 逐级收紧 ====================

    @Test
    void narrowerPolicyWinsOverLooserOne() {
        AiQuotaPolicyEntity global = policy("global", AiConstants.QuotaScope.GLOBAL);
        global.setDailyRequests(1000);
        global.setMaxInputChars(50_000);
        global.setMaxOutputTokens(8000);

        AiQuotaPolicyEntity user = policy("vip_user", AiConstants.QuotaScope.USER);
        user.setDailyRequests(50);
        user.setMaxInputChars(20_000);

        // 依次是 GLOBAL、AGENT、USER 三次查询（当前用户没有角色）
        stubPolicies(List.of(global), List.of(), List.of(user));

        AiQuotaService.Limits limits = service.resolveLimits(1L, agent(2L, null), version(null));

        assertEquals(50, limits.dailyRequests());
        assertEquals(20_000, limits.maxInputChars());
        assertEquals(8000, limits.maxOutputTokens());
        assertEquals(List.of("global", "vip_user"), limits.sources());
    }

    @Test
    void loosingPolicyCannotRaiseAnAlreadyTighterLimit() {
        AiQuotaPolicyEntity global = policy("global", AiConstants.QuotaScope.GLOBAL);
        global.setDailyRequests(10);

        AiQuotaPolicyEntity user = policy("generous", AiConstants.QuotaScope.USER);
        user.setDailyRequests(100_000);

        when(policyMapper.selectList(any())).thenReturn(List.of(global, user));

        assertEquals(
                10, service.resolveLimits(1L, agent(2L, null), version(null)).dailyRequests());
    }

    @Test
    void zeroOrNegativeValuesAreTreatedAsUnset() {
        AiQuotaPolicyEntity policy = policy("broken", AiConstants.QuotaScope.GLOBAL);
        policy.setDailyRequests(0);
        policy.setMonthlyRequests(-1);
        policy.setMaxInputChars(0);
        policy.setMonthlyCostLimit(BigDecimal.ZERO);
        when(policyMapper.selectList(any())).thenReturn(List.of(policy));

        AiQuotaService.Limits limits = service.resolveLimits(1L, agent(2L, null), version(null));

        assertNull(limits.dailyRequests());
        assertNull(limits.monthlyRequests());
        assertNull(limits.monthlyCostLimit());
        // 归零后仍被硬上限兜住，不会变成「无限制」
        assertEquals(properties.getHardMaxInputChars(), limits.maxInputChars());
    }

    @Test
    void monthlyCostLimitTakesTheSmallestPositiveValue() {
        AiQuotaPolicyEntity global = policy("global", AiConstants.QuotaScope.GLOBAL);
        global.setMonthlyCostLimit(new BigDecimal("100.00"));
        AiQuotaPolicyEntity user = policy("user", AiConstants.QuotaScope.USER);
        user.setMonthlyCostLimit(new BigDecimal("26.50"));
        when(policyMapper.selectList(any())).thenReturn(List.of(global, user));

        assertEquals(
                0,
                new BigDecimal("26.50")
                        .compareTo(service.resolveLimits(1L, agent(2L, null), version(null))
                                .monthlyCostLimit()));
    }

    @Test
    void agentBoundPolicyIsAppliedEvenWithoutScopeFilterMatch() {
        AiQuotaPolicyEntity bound = policy("bound", AiConstants.QuotaScope.AGENT);
        bound.setDailyRequests(7);
        when(policyMapper.selectById(55L)).thenReturn(bound);

        AiQuotaService.Limits limits = service.resolveLimits(1L, agent(2L, 55L), version(null));

        assertEquals(7, limits.dailyRequests());
        assertTrue(limits.sources().contains("bound"));
    }

    @Test
    void disabledAgentBoundPolicyIsSkipped() {
        AiQuotaPolicyEntity bound = policy("bound", AiConstants.QuotaScope.AGENT);
        bound.setEnabled(false);
        bound.setDailyRequests(7);
        when(policyMapper.selectById(55L)).thenReturn(bound);

        assertNull(service.resolveLimits(1L, agent(2L, 55L), version(null)).dailyRequests());
    }

    // ==================== 版本覆盖 ====================

    @Test
    void versionOverrideCanOnlyTighten() {
        AiQuotaPolicyEntity global = policy("global", AiConstants.QuotaScope.GLOBAL);
        global.setDailyRequests(100);
        when(policyMapper.selectList(any())).thenReturn(List.of(global));

        AiQuotaService.Limits limits =
                service.resolveLimits(1L, agent(2L, null), version("{\"dailyRequests\":5,\"maxInputChars\":1000}"));

        assertEquals(5, limits.dailyRequests());
        assertEquals(1000, limits.maxInputChars());
        assertTrue(limits.sources().contains("version-override"));
    }

    @Test
    void versionOverrideCannotRaiseThePolicyLimit() {
        AiQuotaPolicyEntity global = policy("global", AiConstants.QuotaScope.GLOBAL);
        global.setDailyRequests(10);
        when(policyMapper.selectList(any())).thenReturn(List.of(global));

        assertEquals(
                10,
                service.resolveLimits(1L, agent(2L, null), version("{\"dailyRequests\":9999}"))
                        .dailyRequests());
    }

    @Test
    void versionOverrideCannotBreakTheHardCaps() {
        AiQuotaService.Limits limits = service.resolveLimits(
                1L, agent(2L, null), version("{\"maxInputChars\":100000000,\"maxOutputTokens\":100000000}"));

        assertEquals(properties.getHardMaxInputChars(), limits.maxInputChars());
        assertEquals(properties.getHardMaxOutputTokens(), limits.maxOutputTokens());
    }

    @Test
    void hardCapsAlsoClampExplicitlyConfiguredPolicies() {
        AiQuotaPolicyEntity policy = policy("too_loose", AiConstants.QuotaScope.GLOBAL);
        policy.setMaxInputChars(1_000_000);
        policy.setMaxContextTokens(1_000_000);
        policy.setMaxOutputTokens(1_000_000);
        when(policyMapper.selectList(any())).thenReturn(List.of(policy));

        AiQuotaService.Limits limits = service.resolveLimits(1L, agent(2L, null), version(null));

        assertEquals(properties.getHardMaxInputChars(), limits.maxInputChars());
        assertEquals(properties.getHardMaxContextTokens(), limits.maxContextTokens());
        assertEquals(properties.getHardMaxOutputTokens(), limits.maxOutputTokens());
    }

    // Stage accounting and concurrency are covered through AiApprovalLifecycleTest with real transactions.

    // ==================== 辅助 ====================

    @SafeVarargs
    private void stubPolicies(List<AiQuotaPolicyEntity>... batches) {
        var stub = when(policyMapper.selectList(any()));
        for (var batch : batches) {
            stub = stub.thenReturn(batch);
        }
        stub.thenReturn(List.of());
    }

    private static AiQuotaPolicyEntity policy(String code, String scope) {
        AiQuotaPolicyEntity policy = new AiQuotaPolicyEntity();
        policy.setId(1L);
        policy.setPolicyCode(code);
        policy.setScopeType(scope);
        policy.setEnabled(true);
        return policy;
    }

    private static AiAgentEntity agent(Long id, Long quotaPolicyId) {
        AiAgentEntity agent = new AiAgentEntity();
        agent.setId(id);
        agent.setAgentCode("music_assistant");
        agent.setQuotaPolicyId(quotaPolicyId);
        return agent;
    }

    private static AiAgentVersionEntity version(String quotaOverrideJson) {
        AiAgentVersionEntity version = new AiAgentVersionEntity();
        version.setId(100L);
        version.setQuotaOverrideJson(quotaOverrideJson);
        return version;
    }
}
