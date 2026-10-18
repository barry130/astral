package com.astral.ai.controller;

import com.astral.ai.dto.AiAdminDtos;
import com.astral.ai.entity.AiAgentEntity;
import com.astral.ai.entity.AiModelEntity;
import com.astral.ai.entity.AiQuotaPolicyEntity;
import com.astral.ai.entity.AiUsageEntity;
import com.astral.ai.service.AiAgentService;
import com.astral.ai.service.AiModelService;
import com.astral.ai.service.AiOverviewService;
import com.astral.ai.service.AiQuotaService;
import com.astral.ai.service.AiUsageService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.common.util.PageQuery;
import com.astral.log.annotation.OperateLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 后台：配置总览、额度策略、用量统计与配置审计。
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
@RequiresPermission(value = "admin:ai:config:view", name = "AI 配置查看", description = "AI 总览与额度查看")
@RequiredArgsConstructor
public class AiAdminUsageController {

    private final AiOverviewService overviewService;
    private final AiQuotaService quotaService;
    private final AiUsageService usageService;
    private final AiAgentService agentService;
    private final AiModelService modelService;

    // ==================== 总览 / 审计 ====================

    @GetMapping("/overview")
    public Result<AiAdminDtos.OverviewVo> overview() {
        return Result.success(overviewService.overview());
    }

    @GetMapping("/audit")
    @RequiresPermission(value = "admin:ai:audit:view", name = "AI 审计查看", description = "查看 AI 配置审计")
    public Result<List<AiAdminDtos.AuditVo>> audit(@RequestParam(required = false) Long pageNum,
                                                   @RequestParam(required = false) Long pageSize) {
        return Result.success(overviewService.audit(PageQuery.pageNum(pageNum == null ? 1 : pageNum),
                PageQuery.pageSize(pageSize == null ? PageQuery.DEFAULT_PAGE_SIZE : pageSize)));
    }

    // ==================== 额度策略 ====================

    @GetMapping("/quotas")
    @RequiresPermission(value = "admin:ai:quota:view", name = "AI 额度查看", description = "查看额度策略")
    public Result<List<AiAdminDtos.QuotaPolicyVo>> quotas() {
        List<AiAdminDtos.QuotaPolicyVo> list = new ArrayList<>();
        for (AiQuotaPolicyEntity entity : quotaService.listPolicies()) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @PostMapping("/quotas")
    @RequiresPermission(value = "admin:ai:quota:edit", name = "AI 额度编辑", description = "新增额度策略")
    @OperateLog("新增 AI 额度策略")
    public Result<AiAdminDtos.QuotaPolicyVo> createQuota(
            @jakarta.validation.Valid @RequestBody AiAdminDtos.QuotaPolicySaveReq req) {
        AiQuotaPolicyEntity entity = new AiQuotaPolicyEntity();
        apply(entity, req);
        entity.setId(null);
        return Result.success("创建成功", toVo(quotaService.savePolicy(entity)));
    }

    @PutMapping("/quotas/{id}")
    @RequiresPermission(value = "admin:ai:quota:edit", name = "AI 额度编辑", description = "修改额度策略")
    @OperateLog("修改 AI 额度策略")
    public Result<AiAdminDtos.QuotaPolicyVo> updateQuota(
            @PathVariable Long id,
            @jakarta.validation.Valid @RequestBody AiAdminDtos.QuotaPolicySaveReq req) {
        AiQuotaPolicyEntity entity = quotaService.requirePolicy(id);
        apply(entity, req);
        return Result.success("保存成功", toVo(quotaService.savePolicy(entity)));
    }

    @DeleteMapping("/quotas/{id}")
    @RequiresPermission(value = "admin:ai:quota:edit", name = "AI 额度编辑", description = "删除额度策略")
    @OperateLog("删除 AI 额度策略")
    public Result<Void> deleteQuota(@PathVariable Long id) {
        quotaService.deletePolicy(id);
        return Result.success("已删除", null);
    }

    // ==================== 用量 ====================

    @GetMapping("/usage")
    @RequiresPermission(value = "admin:ai:usage:view", name = "AI 用量查看", description = "查看 AI 用量明细")
    public Result<List<AiAdminDtos.UsageVo>> usage(@RequestParam(required = false) Long userId,
                                                   @RequestParam(required = false) Long agentId,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) Long pageNum,
                                                   @RequestParam(required = false) Long pageSize) {
        LocalDateTime start = parseStart(from);
        LocalDateTime end = parseEnd(to);
        int num = (int) PageQuery.pageNum(pageNum == null ? 1 : pageNum);
        int size = (int) PageQuery.pageSize(pageSize == null ? PageQuery.DEFAULT_PAGE_SIZE : pageSize);
        List<AiAdminDtos.UsageVo> list = new ArrayList<>();
        for (AiUsageEntity entity : usageService.search(userId, agentId, start, end, num, size)) {
            list.add(toVo(entity));
        }
        return Result.success(list);
    }

    @GetMapping("/usage/summary")
    @RequiresPermission(value = "admin:ai:usage:view", name = "AI 用量查看", description = "查看 AI 用量汇总")
    public Result<AiAdminDtos.UsageSummaryVo> usageSummary(@RequestParam(required = false) Long userId,
                                                           @RequestParam(required = false) Long agentId,
                                                           @RequestParam(required = false) String from,
                                                           @RequestParam(required = false) String to) {
        AiUsageService.Summary summary = usageService.summarize(parseStart(from), parseEnd(to), agentId, userId);
        List<AiAdminDtos.UsageTrendPoint> trend = new ArrayList<>();
        for (AiUsageService.TrendPoint point : summary.trend()) {
            trend.add(new AiAdminDtos.UsageTrendPoint(point.date() == null ? null : point.date().toString(),
                    point.requestCount(), point.inputTokens(), point.outputTokens()));
        }
        return Result.success(new AiAdminDtos.UsageSummaryVo(summary.requestCount(), summary.inputTokens(),
                summary.outputTokens(), summary.estimatedCost(), summary.currency(), trend));
    }

    // ==================== 转换 ====================

    private void apply(AiQuotaPolicyEntity entity, AiAdminDtos.QuotaPolicySaveReq req) {
        entity.setPolicyCode(req.policyCode());
        entity.setName(req.name());
        entity.setScopeType(req.scopeType());
        entity.setAgentId(req.agentId());
        entity.setRoleId(req.roleId());
        entity.setUserId(req.userId());
        entity.setDailyRequests(req.dailyRequests());
        entity.setMonthlyRequests(req.monthlyRequests());
        entity.setDailyInputTokens(req.dailyInputTokens());
        entity.setMonthlyInputTokens(req.monthlyInputTokens());
        entity.setDailyOutputTokens(req.dailyOutputTokens());
        entity.setMonthlyOutputTokens(req.monthlyOutputTokens());
        entity.setMaxInputChars(req.maxInputChars());
        entity.setMaxContextTokens(req.maxContextTokens());
        entity.setMaxOutputTokens(req.maxOutputTokens());
        entity.setMaxConcurrent(req.maxConcurrent());
        entity.setMonthlyCostLimit(req.monthlyCostLimit());
        entity.setEnabled(req.enabled());
        entity.setRemark(req.remark());
    }

    private AiAdminDtos.QuotaPolicyVo toVo(AiQuotaPolicyEntity entity) {
        return new AiAdminDtos.QuotaPolicyVo(entity.getId(), entity.getPolicyCode(), entity.getName(),
                entity.getScopeType(), entity.getAgentId(), entity.getRoleId(), entity.getUserId(),
                entity.getDailyRequests(), entity.getMonthlyRequests(), entity.getDailyInputTokens(),
                entity.getMonthlyInputTokens(), entity.getDailyOutputTokens(), entity.getMonthlyOutputTokens(),
                entity.getMaxInputChars(), entity.getMaxContextTokens(), entity.getMaxOutputTokens(),
                entity.getMaxConcurrent(), entity.getMonthlyCostLimit(), entity.getEnabled(), entity.getRemark(),
                entity.getCreateTime(), entity.getUpdateTime());
    }

    private AiAdminDtos.UsageVo toVo(AiUsageEntity entity) {
        String agentName = null;
        if (entity.getAgentId() != null) {
            try {
                AiAgentEntity agent = agentService.require(entity.getAgentId());
                agentName = agent.getName();
            } catch (RuntimeException ignored) {
                agentName = null;
            }
        }
        String modelCode = null;
        if (entity.getModelId() != null) {
            try {
                AiModelEntity model = modelService.require(entity.getModelId());
                modelCode = model.getModelCode();
            } catch (RuntimeException ignored) {
                modelCode = null;
            }
        }
        return new AiAdminDtos.UsageVo(entity.getId(), entity.getRequestId(), entity.getUserId(),
                entity.getAgentId(), agentName, entity.getProviderId(), entity.getModelId(), modelCode,
                entity.getInputTokens(), entity.getOutputTokens(), entity.getEstimatedCost(),
                entity.getCurrency(), entity.getStatus(), entity.getCreateTime());
    }

    private static LocalDateTime parseStart(String value) {
        LocalDate date = parseDate(value);
        return date == null ? LocalDate.now().minusDays(29).atStartOfDay() : date.atStartOfDay();
    }

    private static LocalDateTime parseEnd(String value) {
        LocalDate date = parseDate(value);
        return date == null ? LocalDate.now().plusDays(1).atStartOfDay() : date.plusDays(1).atStartOfDay();
    }

    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
