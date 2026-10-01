package com.astral.monitor.alert;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.dao.entity.AlertChannel;
import com.astral.dao.entity.AlertRecord;
import com.astral.dao.entity.AlertRule;
import com.astral.dao.mapper.AlertChannelMapper;
import com.astral.dao.mapper.AlertRecordMapper;
import com.astral.dao.mapper.AlertRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 告警管理控制器：渠道 / 规则 / 触发记录
 *
 * <p>权限码 {@code admin:alert:channel:*} / {@code admin:alert:rule:*} 由
 * {@code @RequiresPermission(name=...)} 启动时自动登记进 sys_permission（只增不改）。
 * 写操作为普通运维操作（不发代码、不提权），不设 @RequiresSuper。</p>
 */
@Slf4j
@Validated
@Tag(name = "告警管理")
@RestController
@RequestMapping("/api/v1/admin/alert")
@RequiredArgsConstructor
public class AlertAdminController {

    private final AlertChannelMapper channelMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper recordMapper;
    private final AlertEngine alertEngine;

    // ------------------------------------------------------------------
    // 渠道
    // ------------------------------------------------------------------

    @Operation(summary = "渠道列表")
    @RequiresPermission(value = "admin:alert:channel:view", name = "告警渠道查看", domain = "alert",
            description = "查看告警通知渠道与触发记录")
    @GetMapping("/channel/list")
    public Result<List<AlertChannel>> channelList() {
        return Result.success(channelMapper.selectList(
                new QueryWrapper<AlertChannel>().orderByDesc("create_time")));
    }

    @Data
    public static class ChannelRequest {
        @NotBlank(message = "渠道名称不能为空")
        private String name;
        @NotBlank(message = "渠道类型不能为空")
        private String type;
        @NotBlank(message = "渠道配置不能为空")
        private String config;
        private Integer enabled;
        private String remark;
    }

    @Operation(summary = "新建渠道")
    @RequiresPermission(value = "admin:alert:channel:edit", name = "告警渠道编辑", domain = "alert",
            description = "新建/修改/删除告警渠道")
    @PostMapping("/channel")
    public Result<Void> createChannel(@Valid @RequestBody ChannelRequest body) {
        String configError = alertEngine.getConfigParser().validate(body.getType(), body.getConfig());
        if (configError != null) {
            return Result.fail(configError);
        }
        Long count = channelMapper.selectCount(new QueryWrapper<AlertChannel>().eq("name", body.getName()));
        if (count != null && count > 0) {
            return Result.fail("渠道名称已存在");
        }
        AlertChannel channel = new AlertChannel();
        channel.setName(body.getName().trim());
        channel.setType(body.getType().trim().toUpperCase());
        channel.setConfig(body.getConfig());
        channel.setEnabled(body.getEnabled() == null ? 1 : body.getEnabled());
        channel.setRemark(body.getRemark());
        channelMapper.insert(channel);
        return Result.success();
    }

    @Operation(summary = "修改渠道")
    @RequiresPermission("admin:alert:channel:edit")
    @PutMapping("/channel/{id}")
    public Result<Void> updateChannel(@PathVariable Long id, @Valid @RequestBody ChannelRequest body) {
        AlertChannel channel = channelMapper.selectById(id);
        if (channel == null) {
            return Result.fail("渠道不存在");
        }
        String configError = alertEngine.getConfigParser().validate(body.getType(), body.getConfig());
        if (configError != null) {
            return Result.fail(configError);
        }
        Long count = channelMapper.selectCount(new QueryWrapper<AlertChannel>()
                .eq("name", body.getName()).ne("id", id));
        if (count != null && count > 0) {
            return Result.fail("渠道名称已存在");
        }
        // 白名单更新（MP NOT_NULL 策略下 updateById 跳过 null，语义一致；不透传非预期字段）
        channel.setName(body.getName().trim());
        channel.setType(body.getType().trim().toUpperCase());
        channel.setConfig(body.getConfig());
        channel.setEnabled(body.getEnabled() == null ? channel.getEnabled() : body.getEnabled());
        channel.setRemark(body.getRemark());
        channel.setUpdateTime(LocalDateTime.now());
        channelMapper.updateById(channel);
        return Result.success();
    }

    @Operation(summary = "删除渠道")
    @RequiresPermission("admin:alert:channel:edit")
    @DeleteMapping("/channel/{id}")
    public Result<Void> deleteChannel(@PathVariable Long id) {
        Long used = ruleMapper.selectCount(new QueryWrapper<AlertRule>().eq("channel_id", id));
        if (used != null && used > 0) {
            return Result.fail("仍有规则绑定该渠道，请先调整规则");
        }
        channelMapper.deleteById(id);
        return Result.success();
    }

    @Operation(summary = "渠道测试发送")
    @RequiresPermission("admin:alert:channel:edit")
    @PostMapping("/channel/{id}/test")
    public Result<Void> testChannel(@PathVariable Long id) {
        AlertChannel channel = channelMapper.selectById(id);
        if (channel == null) {
            return Result.fail("渠道不存在");
        }
        try {
            alertEngine.deliver(channel, "[Astral 告警] 测试通知",
                    "这是一条测试通知。渠道：" + channel.getName() + "，时间：" + LocalDateTime.now());
            return Result.success();
        } catch (Exception e) {
            return Result.fail("发送失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 规则
    // ------------------------------------------------------------------

    @Operation(summary = "规则列表")
    @RequiresPermission("admin:alert:channel:view")
    @GetMapping("/rule/list")
    public Result<List<Map<String, Object>>> ruleList() {
        List<AlertRule> rules = ruleMapper.selectList(new QueryWrapper<AlertRule>().orderByDesc("create_time"));
        // 附带渠道名，前端列表少一次 join
        List<AlertChannel> channels = channelMapper.selectList(null);
        return Result.success(rules.stream().map(rule -> {
            Map<String, Object> map = new java.util.LinkedHashMap<>();
            map.put("id", rule.getId());
            map.put("name", rule.getName());
            map.put("metric", rule.getMetric());
            map.put("threshold", rule.getThreshold());
            map.put("windowMinutes", rule.getWindowMinutes());
            map.put("channelId", rule.getChannelId());
            map.put("channelName", channels.stream()
                    .filter(c -> c.getId().equals(rule.getChannelId()))
                    .findFirst().map(AlertChannel::getName).orElse(null));
            map.put("cooldownMinutes", rule.getCooldownMinutes());
            map.put("enabled", rule.getEnabled());
            map.put("lastFiredAt", rule.getLastFiredAt());
            map.put("createTime", rule.getCreateTime());
            return map;
        }).toList());
    }

    @Data
    public static class RuleRequest {
        @NotBlank(message = "规则名称不能为空")
        private String name;
        @NotBlank(message = "指标不能为空")
        private String metric;
        @NotNull(message = "阈值不能为空")
        private Long threshold;
        private Integer windowMinutes;
        @NotNull(message = "渠道不能为空")
        private Long channelId;
        private Integer cooldownMinutes;
        private Integer enabled;
    }

    @Operation(summary = "新建规则")
    @RequiresPermission(value = "admin:alert:rule:edit", name = "告警规则编辑", domain = "alert",
            description = "新建/修改/删除告警规则")
    @PostMapping("/rule")
    public Result<Void> createRule(@Valid @RequestBody RuleRequest body) {
        Result<Void> validation = validateRule(body);
        if (validation != null) {
            return validation;
        }
        Long count = ruleMapper.selectCount(new QueryWrapper<AlertRule>().eq("name", body.getName()));
        if (count != null && count > 0) {
            return Result.fail("规则名称已存在");
        }
        AlertRule rule = new AlertRule();
        rule.setName(body.getName().trim());
        rule.setMetric(AlertEngine.normalizeMetric(body.getMetric()));
        rule.setThreshold(body.getThreshold());
        rule.setWindowMinutes(body.getWindowMinutes() == null ? 5 : body.getWindowMinutes());
        rule.setChannelId(body.getChannelId());
        rule.setCooldownMinutes(body.getCooldownMinutes() == null ? 30 : body.getCooldownMinutes());
        rule.setEnabled(body.getEnabled() == null ? 1 : body.getEnabled());
        ruleMapper.insert(rule);
        return Result.success();
    }

    @Operation(summary = "修改规则")
    @RequiresPermission("admin:alert:rule:edit")
    @PutMapping("/rule/{id}")
    public Result<Void> updateRule(@PathVariable Long id, @Valid @RequestBody RuleRequest body) {
        AlertRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            return Result.fail("规则不存在");
        }
        Result<Void> validation = validateRule(body);
        if (validation != null) {
            return validation;
        }
        Long count = ruleMapper.selectCount(new QueryWrapper<AlertRule>()
                .eq("name", body.getName()).ne("id", id));
        if (count != null && count > 0) {
            return Result.fail("规则名称已存在");
        }
        rule.setName(body.getName().trim());
        rule.setMetric(AlertEngine.normalizeMetric(body.getMetric()));
        rule.setThreshold(body.getThreshold());
        rule.setWindowMinutes(body.getWindowMinutes() == null ? 5 : body.getWindowMinutes());
        rule.setChannelId(body.getChannelId());
        rule.setCooldownMinutes(body.getCooldownMinutes() == null ? 30 : body.getCooldownMinutes());
        rule.setEnabled(body.getEnabled() == null ? rule.getEnabled() : body.getEnabled());
        rule.setUpdateTime(LocalDateTime.now());
        ruleMapper.updateById(rule);
        return Result.success();
    }

    @Operation(summary = "删除规则")
    @RequiresPermission("admin:alert:rule:edit")
    @DeleteMapping("/rule/{id}")
    public Result<Void> deleteRule(@PathVariable Long id) {
        ruleMapper.deleteById(id);
        return Result.success();
    }

    private Result<Void> validateRule(RuleRequest body) {
        String metric = AlertEngine.normalizeMetric(body.getMetric());
        if (!AlertEngine.SUPPORTED_METRICS.contains(metric)) {
            return Result.fail("不支持的指标：" + body.getMetric()
                    + "（可选 SERVER_ERROR_COUNT / CLIENT_ERROR_COUNT / HTTP_5XX_COUNT）");
        }
        if (channelMapper.selectById(body.getChannelId()) == null) {
            return Result.fail("绑定的渠道不存在");
        }
        if (body.getWindowMinutes() != null && (body.getWindowMinutes() < 1 || body.getWindowMinutes() > 1440)) {
            return Result.fail("统计窗口须在 1-1440 分钟之间");
        }
        if (body.getCooldownMinutes() != null && (body.getCooldownMinutes() < 0 || body.getCooldownMinutes() > 10080)) {
            return Result.fail("冷却时间须在 0-10080 分钟之间");
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 触发记录
    // ------------------------------------------------------------------

    @Operation(summary = "触发记录分页")
    @RequiresPermission("admin:alert:channel:view")
    @GetMapping("/record/page")
    public Result<Page<AlertRecord>> recordPage(@RequestParam(defaultValue = "1") long pageNum,
                                                @RequestParam(defaultValue = "10") long pageSize,
                                                @RequestParam(required = false) Long ruleId) {
        QueryWrapper<AlertRecord> qw = new QueryWrapper<>();
        if (ruleId != null) {
            qw.eq("rule_id", ruleId);
        }
        qw.orderByDesc("fired_at");
        return Result.success(recordMapper.selectPage(new Page<>(pageNum, pageSize), qw));
    }
}
