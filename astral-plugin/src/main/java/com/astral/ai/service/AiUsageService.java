package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.mapper.AiModelMapper;
import com.astral.ai.entity.AiRequestEntity;
import com.astral.ai.entity.AiUsageEntity;
import com.astral.ai.mapper.AiUsageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final AiModelMapper modelMapper;

    // ==================== 预占与结算 ====================

    /** 请求开始时预占（记录一条 RESERVED 用量） */
    public AiUsageEntity reserve(AiRequestEntity request, AiModelEntity model) {
        AiUsageEntity entity = base(request, model);
        entity.setInputTokens(0);
        entity.setOutputTokens(0);
        entity.setEstimatedCost(BigDecimal.ZERO);
        entity.setReservationAmount(BigDecimal.ZERO);
        entity.setSettledAmount(BigDecimal.ZERO);
        entity.setStatus(AiConstants.UsageStatus.RESERVED);
        usageMapper.insert(entity);
        return entity;
    }

    /** 请求结束时结算 */
    public void settle(AiUsageEntity usage, int inputTokens, int outputTokens) {
        if (usage == null) {
            return;
        }
        BigDecimal cost = computeCost(usage, inputTokens, outputTokens);
        usage.setInputTokens(inputTokens);
        usage.setOutputTokens(outputTokens);
        usage.setEstimatedCost(cost);
        usage.setSettledAmount(cost);
        usage.setStatus(AiConstants.UsageStatus.SETTLED);
        usageMapper.updateById(usage);
    }

    /** 请求失败时释放预占（保留已产生的真实 token 用量，但费用归零） */
    public void release(AiUsageEntity usage, int inputTokens, int outputTokens) {
        if (usage == null) {
            return;
        }
        usage.setInputTokens(inputTokens);
        usage.setOutputTokens(outputTokens);
        usage.setSettledAmount(BigDecimal.ZERO);
        usage.setStatus(AiConstants.UsageStatus.RELEASED);
        usageMapper.updateById(usage);
    }

    // ==================== 查询 ====================

    /** 按请求取用量（没有返回 null） */
    public AiUsageEntity findByRequest(Long requestId) {
        if (requestId == null) {
            return null;
        }
        List<AiUsageEntity> rows = usageMapper.selectList(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(AiUsageEntity::getRequestId, requestId)
                .orderByDesc(AiUsageEntity::getId)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 用量明细分页 */
    public List<AiUsageEntity> search(Long userId, Long agentId, LocalDateTime from, LocalDateTime to,
                                      int pageNum, int pageSize) {
        int size = Math.max(1, Math.min(200, pageSize));
        int num = Math.max(1, pageNum);
        return usageMapper.selectList(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(userId != null, AiUsageEntity::getUserId, userId)
                .eq(agentId != null, AiUsageEntity::getAgentId, agentId)
                .ge(from != null, AiUsageEntity::getCreateTime, from)
                .le(to != null, AiUsageEntity::getCreateTime, to)
                .orderByDesc(AiUsageEntity::getId)
                .last("limit " + size + " offset " + ((long) (num - 1) * size)));
    }

    /** 区间汇总 */
    public Summary summarize(LocalDateTime from, LocalDateTime to, Long agentId, Long userId) {
        List<AiUsageEntity> rows = usageMapper.selectList(new LambdaQueryWrapper<AiUsageEntity>()
                .eq(agentId != null, AiUsageEntity::getAgentId, agentId)
                .eq(userId != null, AiUsageEntity::getUserId, userId)
                .ge(from != null, AiUsageEntity::getCreateTime, from)
                .le(to != null, AiUsageEntity::getCreateTime, to));
        long requests = 0;
        long input = 0;
        long output = 0;
        BigDecimal cost = BigDecimal.ZERO;
        String currency = DEFAULT_CURRENCY;
        Map<LocalDate, long[]> byDay = new LinkedHashMap<>();
        for (AiUsageEntity row : rows) {
            requests++;
            int in = row.getInputTokens() == null ? 0 : row.getInputTokens();
            int out = row.getOutputTokens() == null ? 0 : row.getOutputTokens();
            input += in;
            output += out;
            if (row.getSettledAmount() != null) {
                cost = cost.add(row.getSettledAmount());
            }
            if (row.getCurrency() != null && !row.getCurrency().isBlank()) {
                currency = row.getCurrency();
            }
            LocalDate day = row.getCreateTime() == null ? LocalDate.now() : row.getCreateTime().toLocalDate();
            long[] slot = byDay.computeIfAbsent(day, key -> new long[3]);
            slot[0] += 1;
            slot[1] += in;
            slot[2] += out;
        }
        List<TrendPoint> trend = new ArrayList<>();
        byDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> trend.add(new TrendPoint(entry.getKey(),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2])));
        return new Summary(requests, input, output, cost.setScale(6, RoundingMode.HALF_UP), currency, trend);
    }

    /** 今日用量（总览页用） */
    public long[] todayTokens() {
        LocalDateTime from = LocalDate.now().atStartOfDay();
        List<AiUsageEntity> rows = usageMapper.selectList(new LambdaQueryWrapper<AiUsageEntity>()
                .ge(AiUsageEntity::getCreateTime, from));
        long in = 0;
        long out = 0;
        for (AiUsageEntity row : rows) {
            in += row.getInputTokens() == null ? 0 : row.getInputTokens();
            out += row.getOutputTokens() == null ? 0 : row.getOutputTokens();
        }
        return new long[]{in, out};
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
        entity.setCurrency(model == null || model.getCurrency() == null || model.getCurrency().isBlank()
                ? DEFAULT_CURRENCY : model.getCurrency());
        return entity;
    }

    private BigDecimal computeCost(AiUsageEntity usage, int inputTokens, int outputTokens) {
        if (usage.getModelId() == null) {
            return BigDecimal.ZERO;
        }
        AiModelEntity model = modelMapper.selectById(usage.getModelId());
        if (model == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal inPrice = model.getInputPrice() == null ? BigDecimal.ZERO : model.getInputPrice();
        BigDecimal outPrice = model.getOutputPrice() == null ? BigDecimal.ZERO : model.getOutputPrice();
        BigDecimal in = inPrice.multiply(BigDecimal.valueOf(inputTokens)).divide(THOUSAND, 8, RoundingMode.HALF_UP);
        BigDecimal out = outPrice.multiply(BigDecimal.valueOf(outputTokens)).divide(THOUSAND, 8, RoundingMode.HALF_UP);
        return in.add(out).setScale(6, RoundingMode.HALF_UP);
    }


    /** 区间汇总结果 */
    public record Summary(long requestCount, long inputTokens, long outputTokens,
                          BigDecimal estimatedCost, String currency, List<TrendPoint> trend) {
    }

    /** 趋势点 */
    public record TrendPoint(LocalDate date, long requestCount, long inputTokens, long outputTokens) {
    }
}
