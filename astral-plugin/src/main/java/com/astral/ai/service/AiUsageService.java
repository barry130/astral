package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.common.AiException;
import com.astral.ai.common.AiPricing;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiUsageEntity;
import com.astral.ai.mapper.AiRequestMapper;
import com.astral.ai.mapper.AiUsageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量与费用服务。
 *
 * <p>用量采取「预占 → 结算」两步（设计文档 §10.2）：请求开始时先按输入字符粗估并预占，
 * 结束时按真实 token 结算，失败则释放预占。预占记录本身也是一条 {@code ai_usage}，
 * 状态为 {@link AiConstants.UsageStatus#RESERVED}，结算后转为 SETTLED / RELEASED。</p>
 *
 * <p>费用按模型配置的「每千 token 单价」计算。没有配置单价时费用记 0，
 * 只统计 token —— 这样本地模型或自建网关不会因为缺价目而无法统计。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService {

    /** 单价按每千 token 计 */
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private static final String DEFAULT_CURRENCY = "CNY";

    private final AiUsageMapper usageMapper;
    private final AiRequestMapper requestMapper;
    private final AiQuotaService quotaService;

    /** One atomic ledger reservation per actual provider call, including resumed calls. */
    @Transactional(rollbackFor = Exception.class)
    public AiUsageEntity reserveStage(
            Long requestId,
            String workerToken,
            String stageKey,
            AiModelEntity model,
            int inputEstimate,
            int outputLimit,
            AiQuotaService.Limits limits) {
        AiRequestEntity request = requestMapper.selectOne(new LambdaQueryWrapper<AiRequestEntity>()
                .eq(AiRequestEntity::getId, requestId)
                .last("FOR UPDATE"));
        if (request == null
                || !AiConstants.RequestStatus.RUNNING.equals(request.getStatus())
                || workerToken == null
                || !workerToken.equals(request.getWorkerToken())
                || request.getLeaseUntil() == null
                || !request.getLeaseUntil().isAfter(LocalDateTime.now())) {
            throw new AiException("AI007", "请求执行权已失效");
        }
        if (inputEstimate < 0 || outputLimit <= 0 || (long) inputEstimate + outputLimit > limits.maxContextTokens()) {
            throw new AiException("AI005", "上下文超出额度上限，请缩短对话或输入");
        }
        AiUsageEntity existing = usageMapper.selectOne(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(AiUsageEntity::getRequestId, requestId)
                .eq(AiUsageEntity::getStageKey, stageKey));
        if (existing != null) {
            throw new AiException("AI007", "模型阶段已经预占，不能重复执行");
        }
        String currency = AiPricing.validate(model.getInputPrice(), model.getOutputPrice(), model.getCurrency());
        if (limits.monthlyCostLimit() != null
                && (model.getInputPrice() == null
                        || model.getOutputPrice() == null
                        || !DEFAULT_CURRENCY.equals(currency))) {
            throw new AiException("AI005", "费用限额要求完整的 CNY 模型价格配置");
        }
        var periods = quotaService.lockPeriods(request.getUserId(), request.getAgentId());
        if (!Boolean.TRUE.equals(request.getQuotaActive())) {
            Long active = requestMapper.selectCount(new LambdaQueryWrapper<AiRequestEntity>()
                    .eq(AiRequestEntity::getUserId, request.getUserId())
                    .eq(AiRequestEntity::getAgentId, request.getAgentId())
                    .eq(AiRequestEntity::getQuotaActive, true));
            if (active >= limits.maxConcurrent()) {
                throw new AiException("AI005", "并发请求过多，请稍后再试");
            }
        }
        AiUsageEntity entity = base(request, model);
        entity.setCurrency(currency);
        entity.setStageKey(stageKey);
        entity.setDayRowId(periods.get(0).getId());
        entity.setMonthRowId(periods.get(1).getId());
        entity.setInputPrice(model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice());
        entity.setOutputPrice(model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice());
        entity.setReservedInputTokens(inputEstimate);
        entity.setReservedOutputTokens(outputLimit);
        entity.setInputTokens(0);
        entity.setOutputTokens(0);
        BigDecimal reserved = stageCost(entity, inputEstimate, outputLimit);
        entity.setReservationAmount(reserved);
        entity.setEstimatedCost(reserved);
        entity.setSettledAmount(BigDecimal.ZERO);
        entity.setStatus(AiConstants.UsageStatus.RESERVED);
        quotaService.reserveStage(
                periods,
                Boolean.TRUE.equals(request.getQuotaCounted()) ? 0 : 1,
                inputEstimate,
                outputLimit,
                cnyAmount(entity, reserved),
                limits);
        usageMapper.insert(entity);
        requestMapper.update(
                null,
                new LambdaUpdateWrapper<AiRequestEntity>()
                        .eq(AiRequestEntity::getId, requestId)
                        .set(AiRequestEntity::getQuotaCounted, true)
                        .set(AiRequestEntity::getQuotaActive, true));
        return entity;
    }

    /** Actual consumption survives a pause, cancellation or later failure. Repeat settlement is a no-op. */
    @Transactional(rollbackFor = Exception.class)
    public void settleStage(Long usageId, int inputTokens, int outputTokens) {
        AiUsageEntity hint = usageMapper.selectById(usageId);
        if (hint == null) {
            throw new AiException("AI005", "阶段用量不存在");
        }
        requestMapper.selectOne(new LambdaQueryWrapper<AiRequestEntity>()
                .eq(AiRequestEntity::getId, hint.getRequestId())
                .last("FOR UPDATE"));
        AiUsageEntity row = usageMapper.selectOne(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(AiUsageEntity::getId, usageId)
                .last("FOR UPDATE"));
        if (!AiConstants.UsageStatus.RESERVED.equals(row.getStatus())
                && !AiConstants.UsageStatus.UNKNOWN.equals(row.getStatus())) {
            return;
        }
        if (inputTokens < 0 || outputTokens < 0) {
            throw new AiException("AI005", "实际用量不能为负数");
        }
        int input = inputTokens;
        int output = outputTokens;
        BigDecimal actual = stageCost(row, input, output);
        quotaService.settleStage(
                row.getDayRowId(),
                row.getMonthRowId(),
                input - row.getReservedInputTokens(),
                output - row.getReservedOutputTokens(),
                cnyAmount(row, row.getReservationAmount()),
                cnyAmount(row, actual));
        usageMapper.update(
                null,
                new LambdaUpdateWrapper<AiUsageEntity>()
                        .eq(AiUsageEntity::getId, usageId)
                        .set(AiUsageEntity::getInputTokens, input)
                        .set(AiUsageEntity::getOutputTokens, output)
                        .set(AiUsageEntity::getReservationAmount, BigDecimal.ZERO)
                        .set(AiUsageEntity::getEstimatedCost, actual)
                        .set(AiUsageEntity::getSettledAmount, actual)
                        .set(AiUsageEntity::getStatus, AiConstants.UsageStatus.SETTLED));
        requestMapper.update(
                null,
                new LambdaUpdateWrapper<AiRequestEntity>()
                        .eq(AiRequestEntity::getId, row.getRequestId())
                        .setSql(
                                "token_in = COALESCE(token_in, 0) + {0}, token_out = COALESCE(token_out, 0) + {1}",
                                input, output));
    }

    /** Keep the reservation as an explicit upper bound; never invent zero consumption on a lost response. */
    public void markUnknown(Long usageId) {
        usageMapper.update(
                null,
                new LambdaUpdateWrapper<AiUsageEntity>()
                        .eq(AiUsageEntity::getId, usageId)
                        .eq(AiUsageEntity::getStatus, AiConstants.UsageStatus.RESERVED)
                        .set(AiUsageEntity::getStatus, AiConstants.UsageStatus.UNKNOWN));
    }

    /** Only the worker that has not entered the provider may release a locally unused reservation. */
    @Transactional(rollbackFor = Exception.class)
    public void releaseUnsentStage(Long usageId) {
        AiUsageEntity hint = usageMapper.selectById(usageId);
        if (hint == null) throw new AiException("AI005", "阶段用量不存在");
        var request = requestMapper.selectOne(new LambdaQueryWrapper<AiRequestEntity>()
                .eq(AiRequestEntity::getId, hint.getRequestId())
                .last("FOR UPDATE"));
        var row = usageMapper.selectOne(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(AiUsageEntity::getId, usageId)
                .last("FOR UPDATE"));
        if (!AiConstants.UsageStatus.RESERVED.equals(row.getStatus())
                && !AiConstants.UsageStatus.UNKNOWN.equals(row.getStatus())) return;
        quotaService.settleStage(
                row.getDayRowId(),
                row.getMonthRowId(),
                -row.getReservedInputTokens(),
                -row.getReservedOutputTokens(),
                cnyAmount(row, row.getReservationAmount()),
                BigDecimal.ZERO);
        usageMapper.update(
                null,
                new LambdaUpdateWrapper<AiUsageEntity>()
                        .eq(AiUsageEntity::getId, usageId)
                        .set(AiUsageEntity::getStatus, AiConstants.UsageStatus.RELEASED)
                        .set(AiUsageEntity::getReservationAmount, BigDecimal.ZERO)
                        .set(AiUsageEntity::getEstimatedCost, BigDecimal.ZERO)
                        .set(AiUsageEntity::getSettledAmount, BigDecimal.ZERO));
        if (Boolean.TRUE.equals(request.getQuotaCounted())
                && usageMapper.selectCount(new LambdaQueryWrapper<AiUsageEntity>()
                                .eq(AiUsageEntity::getRequestId, row.getRequestId())
                                .ne(AiUsageEntity::getStatus, AiConstants.UsageStatus.RELEASED))
                        == 0) {
            quotaService.releaseRequestCount(row.getDayRowId(), row.getMonthRowId());
            requestMapper.update(
                    null,
                    new LambdaUpdateWrapper<AiRequestEntity>()
                            .eq(AiRequestEntity::getId, row.getRequestId())
                            .set(AiRequestEntity::getQuotaCounted, false));
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AiUsageEntity reconcile(Long id, int input, int output, Long operator, String note) {
        if (operator == null || note == null || note.isBlank() || note.length() > 1000 || input < 0 || output < 0) {
            throw new AiException("AI007", "请填写实际用量和 1 至 1000 字的核对依据");
        }
        AiUsageEntity hint = usageMapper.selectById(id);
        if (hint == null) throw new AiException("AI005", "用量记录不存在");
        var request = requestMapper.selectOne(new LambdaQueryWrapper<AiRequestEntity>()
                .eq(AiRequestEntity::getId, hint.getRequestId())
                .last("FOR UPDATE"));
        var row = usageMapper.selectOne(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(AiUsageEntity::getId, id)
                .last("FOR UPDATE"));
        if (request == null
                || !AiConstants.RequestStatus.isTerminal(request.getStatus())
                || !AiConstants.UsageStatus.UNKNOWN.equals(row.getStatus())
                || row.getReconciledAt() != null) {
            throw new AiException("AI005", "仅可核对已结束请求中尚未核对的未知用量");
        }
        if (row.getDayRowId() == null
                || row.getMonthRowId() == null
                || row.getInputPrice() == null
                || row.getOutputPrice() == null) {
            throw new AiException("AI005", "历史记录缺少价格或额度快照，无法自动核对");
        }
        settleStage(id, input, output);
        usageMapper.update(
                null,
                new LambdaUpdateWrapper<AiUsageEntity>()
                        .eq(AiUsageEntity::getId, id)
                        .set(AiUsageEntity::getReconciledBy, operator)
                        .set(AiUsageEntity::getReconciledAt, LocalDateTime.now())
                        .set(AiUsageEntity::getReconciliationNote, note.trim()));
        return usageMapper.selectById(id);
    }

    public void markAbandonedUnknown() {
        usageMapper.update(
                null,
                new LambdaUpdateWrapper<AiUsageEntity>()
                        .eq(AiUsageEntity::getStatus, AiConstants.UsageStatus.RESERVED)
                        .exists(
                                "SELECT 1 FROM ai_request r WHERE r.id = ai_usage.request_id AND r.status IN ('FAILED','CANCELLED')")
                        .set(AiUsageEntity::getStatus, AiConstants.UsageStatus.UNKNOWN));
    }

    private static BigDecimal stageCost(AiUsageEntity row, int input, int output) {
        return row.getInputPrice()
                .multiply(BigDecimal.valueOf(input))
                .add(row.getOutputPrice().multiply(BigDecimal.valueOf(output)))
                .divide(THOUSAND, 6, RoundingMode.HALF_UP);
    }

    private static BigDecimal cnyAmount(AiUsageEntity row, BigDecimal value) {
        return DEFAULT_CURRENCY.equals(row.getCurrency()) ? value : BigDecimal.ZERO;
    }

    // ==================== 查询 ====================

    /** 按请求取用量（没有返回 null） */
    public AiUsageEntity findByRequest(Long requestId) {
        if (requestId == null) {
            return null;
        }
        List<AiUsageEntity> rows = usageMapper.selectList(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(AiUsageEntity::getRequestId, requestId)
                .orderByAsc(AiUsageEntity::getId));
        if (rows.isEmpty()) {
            return null;
        }
        AiUsageEntity total = new AiUsageEntity();
        org.springframework.beans.BeanUtils.copyProperties(rows.getLast(), total);
        total.setInputTokens(rows.stream()
                .mapToInt(row -> row.getInputTokens() == null ? 0 : row.getInputTokens())
                .sum());
        total.setOutputTokens(rows.stream()
                .mapToInt(row -> row.getOutputTokens() == null ? 0 : row.getOutputTokens())
                .sum());
        total.setSettledAmount(rows.stream()
                .map(AiUsageEntity::getSettledAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        total.setReservationAmount(rows.stream()
                .map(AiUsageEntity::getReservationAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        if (rows.stream().map(AiUsageEntity::getCurrency).distinct().count() > 1) {
            total.setCurrency(null);
            total.setSettledAmount(null);
            total.setReservationAmount(null);
            total.setEstimatedCost(null);
        }
        return total;
    }

    /** 用量明细分页 */
    public List<AiUsageEntity> search(
            Long userId, Long agentId, LocalDateTime from, LocalDateTime to, int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(200, pageSize));
        int num = Math.max(1, pageNum);
        return usageMapper.selectList(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(userId != null, AiUsageEntity::getUserId, userId)
                .eq(agentId != null, AiUsageEntity::getAgentId, agentId)
                .ge(from != null, AiUsageEntity::getCreateTime, from)
                .lt(to != null, AiUsageEntity::getCreateTime, to)
                .orderByDesc(AiUsageEntity::getId)
                .last("limit " + size + " offset " + ((long) (num - 1) * size)));
    }

    /** 区间汇总 */
    public Summary summarize(LocalDateTime from, LocalDateTime to, Long agentId, Long userId) {
        LocalDateTime start = from == null ? LocalDate.now().minusDays(29).atStartOfDay() : from;
        LocalDateTime end = to == null ? LocalDate.now().plusDays(1).atStartOfDay() : to;
        if (!start.isBefore(end) || start.plusDays(366).isBefore(end)) {
            throw new AiException("AI007", "统计区间须在 1 至 366 天内");
        }
        var total = usageMapper.totals(start, end, agentId, userId);
        List<CurrencyCost> costs = usageMapper.costs(start, end, agentId, userId).stream()
                .map(row -> new CurrencyCost(
                        row.getCurrency(), row.getSettledAmount(), row.getReservationAmount(), row.getUnknownStages()))
                .toList();
        List<TrendPoint> trend = usageMapper.trend(start, end, agentId, userId).stream()
                .map(row -> new TrendPoint(
                        row.getDate(), row.getRequestCount(), row.getInputTokens(), row.getOutputTokens()))
                .toList();
        return new Summary(
                total.getRequestCount(),
                total.getInputTokens(),
                total.getOutputTokens(),
                costs.size() == 1 ? costs.getFirst().settledAmount() : costs.isEmpty() ? BigDecimal.ZERO : null,
                costs.size() == 1 ? costs.getFirst().currency() : costs.isEmpty() ? DEFAULT_CURRENCY : null,
                costs,
                trend);
    }

    /** 今日用量（总览页用） */
    public long[] todayTokens() {
        LocalDateTime from = LocalDate.now().atStartOfDay();
        var total = usageMapper.totals(from, from.plusDays(1), null, null);
        return new long[] {total.getInputTokens(), total.getOutputTokens()};
    }

    // ==================== 内部 ====================

    private AiUsageEntity base(AiRequestEntity request, AiModelEntity model) {
        AiUsageEntity entity = new AiUsageEntity();
        entity.setRequestId(request.getId());
        entity.setUserId(request.getUserId());
        entity.setAgentId(request.getAgentId());
        entity.setAgentVersionId(request.getAgentVersionId());
        entity.setProviderId(model == null ? request.getProviderId() : model.getProviderId());
        entity.setModelId(model == null ? request.getModelId() : model.getId());
        entity.setCurrency(
                model == null
                                || model.getCurrency() == null
                                || model.getCurrency().isBlank()
                        ? DEFAULT_CURRENCY
                        : model.getCurrency());
        return entity;
    }

    /** 区间汇总结果 */
    public record Summary(
            long requestCount,
            long inputTokens,
            long outputTokens,
            BigDecimal estimatedCost,
            String currency,
            List<CurrencyCost> costs,
            List<TrendPoint> trend) {}

    public record CurrencyCost(
            String currency, BigDecimal settledAmount, BigDecimal reservedAmount, long unknownStages) {}

    /** 趋势点 */
    public record TrendPoint(LocalDate date, long requestCount, long inputTokens, long outputTokens) {}
}
