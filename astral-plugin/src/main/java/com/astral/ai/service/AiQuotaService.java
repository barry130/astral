package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiAgentVersionEntity;
import com.astral.ai.entity.AiQuotaPolicyEntity;
import com.astral.ai.entity.AiUserQuotaEntity;
import com.astral.ai.mapper.AiQuotaPolicyMapper;
import com.astral.ai.mapper.AiUserQuotaMapper;
import com.astral.dao.entity.UserRole;
import com.astral.system.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 额度策略与用量预占服务。
 *
 * <p>优先级（设计文档 §10）：<b>全局安全上限 &gt; 智能体上限 &gt; 角色策略 &gt; 用户例外策略</b>，
 * 逐维度取最严格值——任何一层都可以把额度压小，但没有任何一层能突破硬上限。</p>
 *
 * <p>流程是「预占 → 结算/释放」：预占先把请求数与输入 Token 记进当日/当月行，
 * 上游失败或取消则释放请求数；成功后按真实用量补齐 Token 与费用。这样即使用户中途断开，
 * 额度也不会永远被占住。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiQuotaService {

    /** 并发计数清理的间隔（秒） */
    private static final long CONCURRENCY_SWEEP_SECONDS = 300;

    private final AiQuotaPolicyMapper policyMapper;
    private final AiUserQuotaMapper quotaMapper;
    private final UserRoleService userRoleService;
    private final AiProperties properties;

    /** 运行中请求数（单实例内存计数；多实例部署时应替换为 Redis 原子计数） */
    private final Map<String, Integer> running = new ConcurrentHashMap<>();

    /** 运行中请求的最后活跃时间（用于兜底清理泄漏计数） */
    private final Map<String, Long> runningTouched = new ConcurrentHashMap<>();

    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ai-quota-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    {
        sweeper.scheduleWithFixedDelay(this::sweep, CONCURRENCY_SWEEP_SECONDS,
                CONCURRENCY_SWEEP_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    void shutdown() {
        sweeper.shutdownNow();
    }

    /** 清理超过 1 小时未释放的并发计数，避免进程内计数泄漏 */
    private void sweep() {
        try {
            long deadline = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1);
            runningTouched.entrySet().removeIf(entry -> {
                Long touched = entry.getValue();
                if (touched != null && touched < deadline) {
                    running.remove(entry.getKey());
                    return true;
                }
                return false;
            });
        } catch (Exception e) {
            log.warn("[AI] 并发额度清理失败: {}", e.getMessage());
        }
    }

    /**
     * 生效额度。
     *
     * @param dailyRequests    日请求上限
     * @param monthlyRequests  月请求上限
     * @param dailyInputTokens 日输入 Token 上限
     * @param monthlyInputTokens 月输入 Token 上限
     * @param dailyOutputTokens 日输出 Token 上限
     * @param monthlyOutputTokens 月输出 Token 上限
     * @param maxInputChars    单次输入字符上限
     * @param maxContextTokens 上下文 Token 上限
     * @param maxOutputTokens  输出 Token 上限
     * @param maxConcurrent    并发上限
     * @param monthlyCostLimit 月度费用上限
     * @param sources          命中的策略编码（排障用）
     */
    public record Limits(
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
            List<String> sources
    ) {
    }

    /**
     * 计算某用户在某智能体上的生效额度。
     *
     * @param userId  用户
     * @param agent   智能体
     * @param version 智能体版本（其 quotaOverrideJson 作为最后一层收紧）
     * @return 生效额度
     */
    public Limits resolveLimits(Long userId, AiAgentEntity agent, AiAgentVersionEntity version) {
        List<AiQuotaPolicyEntity> policies = new ArrayList<>();
        policies.addAll(policyMapper.selectList(new LambdaQueryWrapper<AiQuotaPolicyEntity>()
                .eq(AiQuotaPolicyEntity::getEnabled, true)
                .eq(AiQuotaPolicyEntity::getScopeType, AiConstants.QuotaScope.GLOBAL)));

        if (agent != null && agent.getId() != null) {
            policies.addAll(policyMapper.selectList(new LambdaQueryWrapper<AiQuotaPolicyEntity>()
                    .eq(AiQuotaPolicyEntity::getEnabled, true)
                    .eq(AiQuotaPolicyEntity::getScopeType, AiConstants.QuotaScope.AGENT)
                    .eq(AiQuotaPolicyEntity::getAgentId, agent.getId())));
            // 智能体可以直接绑定一条策略
            if (agent.getQuotaPolicyId() != null) {
                AiQuotaPolicyEntity bound = policyMapper.selectById(agent.getQuotaPolicyId());
                if (bound != null && Boolean.TRUE.equals(bound.getEnabled())) {
                    policies.add(bound);
                }
            }
        }

        List<Long> roleIds = roleIdsOf(userId);
        if (!roleIds.isEmpty()) {
            policies.addAll(policyMapper.selectList(new LambdaQueryWrapper<AiQuotaPolicyEntity>()
                    .eq(AiQuotaPolicyEntity::getEnabled, true)
                    .eq(AiQuotaPolicyEntity::getScopeType, AiConstants.QuotaScope.ROLE)
                    .in(AiQuotaPolicyEntity::getRoleId, roleIds)));
        }
        if (userId != null) {
            policies.addAll(policyMapper.selectList(new LambdaQueryWrapper<AiQuotaPolicyEntity>()
                    .eq(AiQuotaPolicyEntity::getEnabled, true)
                    .eq(AiQuotaPolicyEntity::getScopeType, AiConstants.QuotaScope.USER)
                    .eq(AiQuotaPolicyEntity::getUserId, userId)));
        }

        List<String> sources = new ArrayList<>();
        Integer dailyRequests = null;
        Integer monthlyRequests = null;
        Long dailyIn = null;
        Long monthlyIn = null;
        Long dailyOut = null;
        Long monthlyOut = null;
        Integer maxInputChars = null;
        Integer maxContext = null;
        Integer maxOutput = null;
        Integer maxConcurrent = null;
        BigDecimal costLimit = null;

        for (AiQuotaPolicyEntity policy : policies) {
            if (policy.getDailyRequests() != null && policy.getDailyRequests() > 0) {
                dailyRequests = min(dailyRequests, policy.getDailyRequests());
            }
            if (policy.getMonthlyRequests() != null && policy.getMonthlyRequests() > 0) {
                monthlyRequests = min(monthlyRequests, policy.getMonthlyRequests());
            }
            if (policy.getDailyInputTokens() != null && policy.getDailyInputTokens() > 0) {
                dailyIn = min(dailyIn, policy.getDailyInputTokens());
            }
            if (policy.getMonthlyInputTokens() != null && policy.getMonthlyInputTokens() > 0) {
                monthlyIn = min(monthlyIn, policy.getMonthlyInputTokens());
            }
            if (policy.getDailyOutputTokens() != null && policy.getDailyOutputTokens() > 0) {
                dailyOut = min(dailyOut, policy.getDailyOutputTokens());
            }
            if (policy.getMonthlyOutputTokens() != null && policy.getMonthlyOutputTokens() > 0) {
                monthlyOut = min(monthlyOut, policy.getMonthlyOutputTokens());
            }
            if (policy.getMaxInputChars() != null && policy.getMaxInputChars() > 0) {
                maxInputChars = min(maxInputChars, policy.getMaxInputChars());
            }
            if (policy.getMaxContextTokens() != null && policy.getMaxContextTokens() > 0) {
                maxContext = min(maxContext, policy.getMaxContextTokens());
            }
            if (policy.getMaxOutputTokens() != null && policy.getMaxOutputTokens() > 0) {
                maxOutput = min(maxOutput, policy.getMaxOutputTokens());
            }
            if (policy.getMaxConcurrent() != null && policy.getMaxConcurrent() > 0) {
                maxConcurrent = min(maxConcurrent, policy.getMaxConcurrent());
            }
            if (policy.getMonthlyCostLimit() != null && policy.getMonthlyCostLimit().compareTo(BigDecimal.ZERO) > 0) {
                costLimit = costLimit == null || policy.getMonthlyCostLimit().compareTo(costLimit) < 0
                        ? policy.getMonthlyCostLimit() : costLimit;
            }
            sources.add(policy.getPolicyCode());
        }

        // 版本层的额度覆盖同样只能收紧
        if (version != null && version.getQuotaOverrideJson() != null
                && !version.getQuotaOverrideJson().isBlank()) {
            var node = AiJson.readObject(version.getQuotaOverrideJson());
            if (node != null) {
                dailyRequests = shrink(dailyRequests, AiJson.intValue(node, "dailyRequests", 0));
                monthlyRequests = shrink(monthlyRequests, AiJson.intValue(node, "monthlyRequests", 0));
                dailyIn = shrinkLong(dailyIn, (long) AiJson.intValue(node, "dailyInputTokens", 0));
                monthlyIn = shrinkLong(monthlyIn, (long) AiJson.intValue(node, "monthlyInputTokens", 0));
                dailyOut = shrinkLong(dailyOut, (long) AiJson.intValue(node, "dailyOutputTokens", 0));
                monthlyOut = shrinkLong(monthlyOut, (long) AiJson.intValue(node, "monthlyOutputTokens", 0));
                maxInputChars = shrink(maxInputChars, AiJson.intValue(node, "maxInputChars", 0));
                maxContext = shrink(maxContext, AiJson.intValue(node, "maxContextTokens", 0));
                maxOutput = shrink(maxOutput, AiJson.intValue(node, "maxOutputTokens", 0));
                maxConcurrent = shrink(maxConcurrent, AiJson.intValue(node, "maxConcurrent", 0));
                sources.add("version-override");
            }
        }

        // 硬上限兜底：任何配置都不能突破进程级安全阈值
        maxInputChars = shrink(maxInputChars, properties.getHardMaxInputChars());
        maxContext = shrink(maxContext, properties.getHardMaxContextTokens());
        maxOutput = shrink(maxOutput, properties.getHardMaxOutputTokens());
        if (maxInputChars == null || maxInputChars <= 0) {
            maxInputChars = properties.getHardMaxInputChars();
        }
        if (maxContext == null || maxContext <= 0) {
            maxContext = properties.getHardMaxContextTokens();
        }
        if (maxOutput == null || maxOutput <= 0) {
            maxOutput = properties.getHardMaxOutputTokens();
        }
        if (maxConcurrent == null || maxConcurrent <= 0) {
            maxConcurrent = 3;
        }
        return new Limits(dailyRequests, monthlyRequests, dailyIn, monthlyIn, dailyOut, monthlyOut,
                maxInputChars, maxContext, maxOutput, maxConcurrent, costLimit, sources);
    }

    /**
     * 预占额度：校验输入长度、并发、日/月请求数，并原子累加计数。
     *
     * @param userId    用户
     * @param agent     智能体
     * @param version   版本
     * @param inputChars 输入字符数
     * @param limits    生效额度
     * @return 预占凭据
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(Long userId, AiAgentEntity agent, AiAgentVersionEntity version,
                               int inputChars, Limits limits) {
        if (inputChars > limits.maxInputChars()) {
            throw new AiException("AI005", "输入过长：" + inputChars + " 字符，上限 " + limits.maxInputChars());
        }
        String concurrencyKey = userId + ":" + (agent == null ? 0 : agent.getId());
        int current = running.merge(concurrencyKey, 1, Integer::sum);
        if (current > limits.maxConcurrent()) {
            running.merge(concurrencyKey, -1, Integer::sum);
            throw new AiException("AI005", "并发请求过多（上限 " + limits.maxConcurrent() + "），请稍后再试");
        }
        runningTouched.put(concurrencyKey, System.currentTimeMillis());
        try {
            Long agentId = agent == null ? 0L : agent.getId();
            AiUserQuotaEntity day = ensureRow(userId, agentId, "DAY", LocalDate.now());
            AiUserQuotaEntity month = ensureRow(userId, agentId, "MONTH", LocalDate.now().withDayOfMonth(1));
            int dayRows = quotaMapper.addWithinLimits(day.getId(), 1, inputChars, 0,
                    limits.dailyRequests(), limits.dailyInputTokens(), limits.dailyOutputTokens());
            if (dayRows == 0) {
                releaseConcurrency(concurrencyKey);
                throw new AiException("AI005", "今日额度已用尽");
            }
            int monthRows = quotaMapper.addWithinLimits(month.getId(), 1, inputChars, 0,
                    limits.monthlyRequests(), limits.monthlyInputTokens(), limits.monthlyOutputTokens());
            if (monthRows == 0) {
                quotaMapper.addWithinLimits(day.getId(), -1, -inputChars, 0, null, null, null);
                releaseConcurrency(concurrencyKey);
                throw new AiException("AI005", "本月额度已用尽");
            }
            return new Reservation(userId, agentId, day.getId(), month.getId(), inputChars,
                    concurrencyKey, limits);
        } catch (AiException e) {
            throw e;
        } catch (RuntimeException e) {
            releaseConcurrency(concurrencyKey);
            throw e;
        }
    }

    /**
     * 结算：补齐真实 Token 与费用。
     *
     * @param reservation 预占凭据
     * @param outputChars 输出字符数（按 4 字符 ≈ 1 token 粗估，真实值由上游返回）
     */
    public void settle(Reservation reservation, int outputChars) {
        try {
            releaseConcurrency(reservation.concurrencyKey());
            long outTokens = Math.max(1, outputChars / 2L);
            quotaMapper.addWithinLimits(reservation.dayRowId(), 0, 0, outTokens, null, null, null);
            quotaMapper.addWithinLimits(reservation.monthRowId(), 0, 0, outTokens, null, null, null);
        } catch (Exception e) {
            log.warn("[AI] 额度结算失败 user={} reason={}", reservation.userId(), e.getMessage());
        }
    }

    /** 释放：上游失败或取消时归还请求数，避免白扣 */
    public void release(Reservation reservation) {
        try {
            releaseConcurrency(reservation.concurrencyKey());
            quotaMapper.addWithinLimits(reservation.dayRowId(), -1, -reservation.inputChars(), 0, null, null, null);
            quotaMapper.addWithinLimits(reservation.monthRowId(), -1, -reservation.inputChars(), 0, null, null, null);
        } catch (Exception e) {
            log.warn("[AI] 额度释放失败 user={} reason={}", reservation.userId(), e.getMessage());
        }
    }

    private void releaseConcurrency(String key) {
        if (key == null) {
            return;
        }
        running.compute(key, (k, v) -> v == null || v <= 1 ? null : v - 1);
        runningTouched.remove(key);
    }

    /** 取或创建周期额度行（唯一索引 + 咨询锁保证并发安全） */
    private AiUserQuotaEntity ensureRow(Long userId, Long agentId, String periodType, LocalDate start) {
        AiUserQuotaEntity existing = quotaMapper.selectOne(new LambdaQueryWrapper<AiUserQuotaEntity>()
                .eq(AiUserQuotaEntity::getUserId, userId)
                .eq(AiUserQuotaEntity::getAgentId, agentId)
                .eq(AiUserQuotaEntity::getPeriodType, periodType)
                .eq(AiUserQuotaEntity::getPeriodStart, start)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        quotaMapper.lockQuotaKey(userId + ":" + agentId + ":" + periodType + ":" + start);
        existing = quotaMapper.selectOne(new LambdaQueryWrapper<AiUserQuotaEntity>()
                .eq(AiUserQuotaEntity::getUserId, userId)
                .eq(AiUserQuotaEntity::getAgentId, agentId)
                .eq(AiUserQuotaEntity::getPeriodType, periodType)
                .eq(AiUserQuotaEntity::getPeriodStart, start)
                .last("limit 1"));
        if (existing != null) {
            return existing;
        }
        AiUserQuotaEntity row = new AiUserQuotaEntity();
        row.setUserId(userId);
        row.setAgentId(agentId);
        row.setPeriodType(periodType);
        row.setPeriodStart(start);
        row.setPeriodEnd("DAY".equals(periodType) ? start : start.withDayOfMonth(start.lengthOfMonth()));
        row.setRequestCount(0);
        row.setInputTokens(0L);
        row.setOutputTokens(0L);
        row.setReservedCost(BigDecimal.ZERO);
        row.setSettledCost(BigDecimal.ZERO);
        row.setVersion(0);
        quotaMapper.insert(row);
        return row;
    }

    /** 取用户角色 ID */
    private List<Long> roleIdsOf(Long userId) {
        if (userId == null) {
            return List.of();
        }
        try {
            return userRoleService.list(new LambdaQueryWrapper<UserRole>()
                            .eq(UserRole::getUserId, userId))
                    .stream()
                    .map(UserRole::getRoleId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.debug("[AI] 读取用户角色失败 user={} reason={}", userId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 预占凭据。
     *
     * @param userId         用户
     * @param agentId        智能体（0 表示不限）
     * @param dayRowId       当日额度行
     * @param monthRowId     当月额度行
     * @param inputChars     预占的输入字符数
     * @param concurrencyKey 并发计数键
     * @param limits         生效额度
     */
    public record Reservation(Long userId, Long agentId, Long dayRowId, Long monthRowId,
                              int inputChars, String concurrencyKey, Limits limits) {
    }

    // ==================== 策略维护 ====================

    /** 全部额度策略 */
    public List<AiQuotaPolicyEntity> listPolicies() {
        return policyMapper.selectList(new LambdaQueryWrapper<AiQuotaPolicyEntity>()
                .orderByAsc(AiQuotaPolicyEntity::getId));
    }

    /** 取策略 */
    public AiQuotaPolicyEntity requirePolicy(Long id) {
        AiQuotaPolicyEntity entity = id == null ? null : policyMapper.selectById(id);
        if (entity == null) {
            throw new AiException("AI007", "额度策略不存在: " + id);
        }
        return entity;
    }

    /** 新增或更新策略（按 id 是否为空区分） */
    @Transactional(rollbackFor = Exception.class)
    public AiQuotaPolicyEntity savePolicy(AiQuotaPolicyEntity input) {
        if (input.getPolicyCode() == null || input.getPolicyCode().isBlank()) {
            throw new AiException("AI007", "策略编码不能为空");
        }
        if (!AiConstants.QuotaScope.all().contains(input.getScopeType())) {
            throw new AiException("AI007", "作用域非法: " + input.getScopeType());
        }
        AiQuotaPolicyEntity entity = input.getId() == null ? null : policyMapper.selectById(input.getId());
        boolean isNew = entity == null;
        if (isNew) {
            entity = new AiQuotaPolicyEntity();
            entity.setPolicyCode(input.getPolicyCode());
        }
        entity.setName(input.getName());
        entity.setScopeType(input.getScopeType());
        entity.setAgentId(input.getAgentId());
        entity.setRoleId(input.getRoleId());
        entity.setUserId(input.getUserId());
        entity.setDailyRequests(input.getDailyRequests());
        entity.setMonthlyRequests(input.getMonthlyRequests());
        entity.setDailyInputTokens(input.getDailyInputTokens());
        entity.setMonthlyInputTokens(input.getMonthlyInputTokens());
        entity.setDailyOutputTokens(input.getDailyOutputTokens());
        entity.setMonthlyOutputTokens(input.getMonthlyOutputTokens());
        entity.setMaxInputChars(input.getMaxInputChars());
        entity.setMaxContextTokens(input.getMaxContextTokens());
        entity.setMaxOutputTokens(input.getMaxOutputTokens());
        entity.setMaxConcurrent(input.getMaxConcurrent());
        entity.setMonthlyCostLimit(input.getMonthlyCostLimit());
        entity.setEnabled(input.getEnabled() == null || input.getEnabled());
        entity.setRemark(input.getRemark());
        if (isNew) {
            policyMapper.insert(entity);
        } else {
            policyMapper.updateById(entity);
        }
        return entity;
    }

    /** 删除策略 */
    @Transactional(rollbackFor = Exception.class)
    public void deletePolicy(Long id) {
        requirePolicy(id);
        policyMapper.deleteById(id);
    }

    private static Integer min(Integer current, Integer candidate) {
        if (candidate == null || candidate <= 0) {
            return current;
        }
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static Long min(Long current, Long candidate) {
        if (candidate == null || candidate <= 0) {
            return current;
        }
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static Integer shrink(Integer current, Integer candidate) {
        if (candidate == null || candidate <= 0) {
            return current;
        }
        return current == null ? candidate : Math.min(current, candidate);
    }

    private static Long shrinkLong(Long current, Long candidate) {
        if (candidate == null || candidate <= 0) {
            return current;
        }
        return current == null ? candidate : Math.min(current, candidate);
    }
}
