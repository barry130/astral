package com.astral.monitor.alert;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.AlertChannel;
import com.astral.dao.entity.AlertRecord;
import com.astral.dao.entity.AlertRule;
import com.astral.dao.mapper.AlertChannelMapper;
import com.astral.dao.mapper.AlertRecordMapper;
import com.astral.dao.mapper.AlertRuleMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

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
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin/alert")
@RequiredArgsConstructor
public class AlertAdminController {

    private final AlertChannelMapper channelMapper;
    private final AlertRuleMapper ruleMapper;
    private final AlertRecordMapper recordMapper;
    private final AlertEngine alertEngine;
    private static final String MASKED_CONFIG_VALUE = "********";
    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------
    // 渠道
    // ------------------------------------------------------------------

    @Operation(summary = "渠道列表")
    @RequiresPermission(
            value = "admin:alert:channel:view",
            name = "告警渠道查看",
            domain = "alert",
            description = "查看告警通知渠道与触发记录")
    @GetMapping("/channel/list")
    public Result<List<ChannelView>> channelList() {
        return Result.success(
                channelMapper.selectList(new QueryWrapper<AlertChannel>().orderByDesc("create_time")).stream()
                        .map(row -> new ChannelView(
                                row.getId(),
                                row.getName(),
                                row.getType(),
                                maskedConfig(row),
                                row.getEnabled(),
                                row.getRemark(),
                                row.getCreateTime(),
                                row.getUpdateTime()))
                        .toList());
    }

    public record ChannelView(
            Long id,
            String name,
            String type,
            String config,
            Integer enabled,
            String remark,
            LocalDateTime createTime,
            LocalDateTime updateTime) {}

    private static String maskedConfig(AlertChannel channel) {
        Map<String, String> config = new AlertConfigParser().parse(channel.getConfig());
        Map<String, String> visible = new LinkedHashMap<>();
        if (AlertEngine.CHANNEL_EMAIL.equalsIgnoreCase(channel.getType())) {
            visible.put("to", config.get("to"));
        } else if (AlertEngine.CHANNEL_SMS.equalsIgnoreCase(channel.getType())) {
            visible.put("phone", config.get("phone"));
        } else if (AlertEngine.CHANNEL_WEBHOOK.equalsIgnoreCase(channel.getType())) {
            visible.put("url", masked(config.get("url")));
            visible.put("secret", masked(config.get("secret")));
            visible.put("header", config.get("header"));
        }
        return JSON.writeValueAsString(visible);
    }

    private static String masked(String value) {
        return value == null || value.isEmpty() ? value : MASKED_CONFIG_VALUE;
    }

    private static String writableConfig(String type, String raw, AlertChannel existing) {
        Map<String, String> incoming;
        try {
            Map<?, ?> parsed = JSON.readValue(raw, Map.class);
            if (parsed == null) {
                throw BusinessException.of("渠道配置必须是 JSON 对象");
            }
            incoming = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : parsed.entrySet()) {
                if (!(entry.getKey() instanceof String key)
                        || (entry.getValue() != null && !(entry.getValue() instanceof String))) {
                    throw BusinessException.of("渠道配置值必须是字符串或 null");
                }
                incoming.put(key, (String) entry.getValue());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw BusinessException.of("渠道配置必须是合法 JSON 对象");
        }
        String normalizedType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        List<String> fields = switch (normalizedType) {
            case AlertEngine.CHANNEL_WEBHOOK -> List.of("url", "secret", "header");
            case AlertEngine.CHANNEL_EMAIL -> List.of("to");
            case AlertEngine.CHANNEL_SMS -> List.of("phone");
            default -> List.of();
        };
        if (!fields.containsAll(incoming.keySet())) {
            throw BusinessException.of("渠道配置包含不支持的字段");
        }
        if (AlertEngine.CHANNEL_WEBHOOK.equals(normalizedType)) {
            Map<String, String> previous = existing != null && normalizedType.equalsIgnoreCase(existing.getType())
                    ? new AlertConfigParser().parse(existing.getConfig())
                    : Map.of();
            for (String field : List.of("url", "secret")) {
                if (MASKED_CONFIG_VALUE.equals(incoming.get(field))) {
                    String value = previous.get(field);
                    if (value == null || value.isEmpty()) {
                        throw BusinessException.of("没有可保留的配置，请重新填写 " + field);
                    }
                    incoming.put(field, value);
                }
            }
        }
        return JSON.writeValueAsString(incoming);
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
    @RequiresPermission(
            value = "admin:alert:channel:edit",
            name = "告警渠道编辑",
            domain = "alert",
            description = "新建/修改/删除告警渠道")
    @PostMapping("/channel")
    public Result<Void> createChannel(@Valid @RequestBody ChannelRequest body) {
        String config = writableConfig(body.getType(), body.getConfig(), null);
        String configError = alertEngine.validateChannelConfig(body.getType(), config);
        if (configError != null) {
            throw BusinessException.of(configError);
        }
        Long count = channelMapper.selectCount(new QueryWrapper<AlertChannel>().eq("name", body.getName()));
        if (count != null && count > 0) {
            throw BusinessException.of("渠道名称已存在");
        }
        AlertChannel channel = new AlertChannel();
        channel.setName(body.getName().trim());
        channel.setType(body.getType().trim().toUpperCase(Locale.ROOT));
        channel.setConfig(config);
        channel.setEnabled(body.getEnabled() == null ? 1 : body.getEnabled());
        channel.setRemark(body.getRemark());
        try {
            channelMapper.insert(channel);
        } catch (DuplicateKeyException e) {
            // 并发同名创建：唯一索引是最终防线，不能把数据库约束异常直接暴露成 500
            throw BusinessException.of("渠道名称已存在");
        }
        return Result.success();
    }

    @Operation(summary = "修改渠道")
    @RequiresPermission("admin:alert:channel:edit")
    @PutMapping("/channel/{id}")
    public Result<Void> updateChannel(@PathVariable Long id, @Valid @RequestBody ChannelRequest body) {
        AlertChannel channel = channelMapper.selectById(id);
        if (channel == null) {
            throw BusinessException.of("渠道不存在");
        }
        String config = writableConfig(body.getType(), body.getConfig(), channel);
        String configError = alertEngine.validateChannelConfig(body.getType(), config);
        if (configError != null) {
            throw BusinessException.of(configError);
        }
        Long count = channelMapper.selectCount(
                new QueryWrapper<AlertChannel>().eq("name", body.getName()).ne("id", id));
        if (count != null && count > 0) {
            throw BusinessException.of("渠道名称已存在");
        }
        // 白名单更新：只 SET 请求允许修改的列，避免把读取时的整行旧值回写覆盖并发编辑
        try {
            channelMapper.update(
                    null,
                    new LambdaUpdateWrapper<AlertChannel>()
                            .eq(AlertChannel::getId, id)
                            .set(AlertChannel::getName, body.getName().trim())
                            .set(AlertChannel::getType, body.getType().trim().toUpperCase(Locale.ROOT))
                            .set(AlertChannel::getConfig, config)
                            .set(body.getEnabled() != null, AlertChannel::getEnabled, body.getEnabled())
                            .set(AlertChannel::getRemark, body.getRemark())
                            .set(AlertChannel::getUpdateTime, LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            throw BusinessException.of("渠道名称已存在");
        }
        return Result.success();
    }

    @Operation(summary = "删除渠道")
    @RequiresPermission("admin:alert:channel:edit")
    @DeleteMapping("/channel/{id}")
    public Result<Void> deleteChannel(@PathVariable Long id) {
        if (channelMapper.selectById(id) == null) {
            throw BusinessException.of("渠道不存在");
        }
        Long used = ruleMapper.selectCount(new QueryWrapper<AlertRule>().eq("channel_id", id));
        if (used != null && used > 0) {
            throw BusinessException.of("仍有规则绑定该渠道，请先调整规则");
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
            throw BusinessException.of("渠道不存在");
        }
        try {
            alertEngine.deliver(
                    channel, "[Astral 告警] 测试通知", "这是一条测试通知。渠道：" + channel.getName() + "，时间：" + LocalDateTime.now());
            return Result.success();
        } catch (Exception e) {
            throw BusinessException.of("发送失败：" + e.getMessage());
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
        return Result.success(rules.stream()
                .map(rule -> {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("id", rule.getId());
                    map.put("name", rule.getName());
                    map.put("metric", rule.getMetric());
                    map.put("threshold", rule.getThreshold());
                    map.put("windowMinutes", rule.getWindowMinutes());
                    map.put("channelId", rule.getChannelId());
                    map.put(
                            "channelName",
                            channels.stream()
                                    .filter(c -> c.getId().equals(rule.getChannelId()))
                                    .findFirst()
                                    .map(AlertChannel::getName)
                                    .orElse(null));
                    map.put("cooldownMinutes", rule.getCooldownMinutes());
                    map.put("enabled", rule.getEnabled());
                    map.put("lastFiredAt", rule.getLastFiredAt());
                    map.put("createTime", rule.getCreateTime());
                    return map;
                })
                .toList());
    }

    @Data
    public static class RuleRequest {
        @NotBlank(message = "规则名称不能为空")
        private String name;

        @NotBlank(message = "指标不能为空")
        private String metric;

        @NotNull(message = "阈值不能为空")
        @Min(value = 1, message = "阈值必须大于 0")
        private Long threshold;

        private Integer windowMinutes;

        @NotNull(message = "渠道不能为空")
        private Long channelId;

        private Integer cooldownMinutes;
        private Integer enabled;
    }

    @Operation(summary = "新建规则")
    @RequiresPermission(
            value = "admin:alert:rule:edit",
            name = "告警规则编辑",
            domain = "alert",
            description = "新建/修改/删除告警规则")
    @PostMapping("/rule")
    public Result<Void> createRule(@Valid @RequestBody RuleRequest body) {
        validateRule(body);
        Long count = ruleMapper.selectCount(new QueryWrapper<AlertRule>().eq("name", body.getName()));
        if (count != null && count > 0) {
            throw BusinessException.of("规则名称已存在");
        }
        AlertRule rule = new AlertRule();
        rule.setName(body.getName().trim());
        rule.setMetric(AlertEngine.normalizeMetric(body.getMetric()));
        rule.setThreshold(body.getThreshold());
        rule.setWindowMinutes(body.getWindowMinutes() == null ? 5 : body.getWindowMinutes());
        rule.setChannelId(body.getChannelId());
        rule.setCooldownMinutes(body.getCooldownMinutes() == null ? 30 : body.getCooldownMinutes());
        rule.setEnabled(body.getEnabled() == null ? 1 : body.getEnabled());
        try {
            ruleMapper.insert(rule);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of("规则名称已存在");
        }
        return Result.success();
    }

    @Operation(summary = "修改规则")
    @RequiresPermission("admin:alert:rule:edit")
    @PutMapping("/rule/{id}")
    public Result<Void> updateRule(@PathVariable Long id, @Valid @RequestBody RuleRequest body) {
        AlertRule rule = ruleMapper.selectById(id);
        if (rule == null) {
            throw BusinessException.of("规则不存在");
        }
        validateRule(body);
        Long count = ruleMapper.selectCount(
                new QueryWrapper<AlertRule>().eq("name", body.getName()).ne("id", id));
        if (count != null && count > 0) {
            throw BusinessException.of("规则名称已存在");
        }
        // 白名单更新：只改规则字段，避免整行回写覆盖评估线程刚更新的 last_fired_at
        try {
            int updated = ruleMapper.update(
                    null,
                    new LambdaUpdateWrapper<AlertRule>()
                            .eq(AlertRule::getId, id)
                            .set(AlertRule::getName, body.getName().trim())
                            .set(AlertRule::getMetric, AlertEngine.normalizeMetric(body.getMetric()))
                            .set(AlertRule::getThreshold, body.getThreshold())
                            .set(
                                    AlertRule::getWindowMinutes,
                                    body.getWindowMinutes() == null ? 5 : body.getWindowMinutes())
                            .set(AlertRule::getChannelId, body.getChannelId())
                            .set(
                                    AlertRule::getCooldownMinutes,
                                    body.getCooldownMinutes() == null ? 30 : body.getCooldownMinutes())
                            .set(body.getEnabled() != null, AlertRule::getEnabled, body.getEnabled())
                            .set(AlertRule::getUpdateTime, LocalDateTime.now()));
            if (updated != 1) throw BusinessException.of("规则已变更，请刷新后重试");
        } catch (DuplicateKeyException e) {
            throw BusinessException.of("规则名称已存在");
        }
        return Result.success();
    }

    @Operation(summary = "删除规则")
    @RequiresPermission("admin:alert:rule:edit")
    @DeleteMapping("/rule/{id}")
    public Result<Void> deleteRule(@PathVariable Long id) {
        if (ruleMapper.selectById(id) == null) {
            throw BusinessException.of("规则不存在");
        }
        ruleMapper.deleteById(id);
        return Result.success();
    }

    private void validateRule(RuleRequest body) {
        String metric = AlertEngine.normalizeMetric(body.getMetric());
        if (!AlertEngine.SUPPORTED_METRICS.contains(metric)) {
            throw BusinessException.of(
                    "不支持的指标：" + body.getMetric() + "（可选 SERVER_ERROR_COUNT / CLIENT_ERROR_COUNT / HTTP_5XX_COUNT）");
        }
        if (channelMapper.selectById(body.getChannelId()) == null) {
            throw BusinessException.of("绑定的渠道不存在");
        }
        if (body.getWindowMinutes() != null && (body.getWindowMinutes() < 1 || body.getWindowMinutes() > 1440)) {
            throw BusinessException.of("统计窗口须在 1-1440 分钟之间");
        }
        if (body.getCooldownMinutes() != null && (body.getCooldownMinutes() < 0 || body.getCooldownMinutes() > 10080)) {
            throw BusinessException.of("冷却时间须在 0-10080 分钟之间");
        }
    }

    // ------------------------------------------------------------------
    // 触发记录
    // ------------------------------------------------------------------

    @Operation(summary = "触发记录分页")
    @RequiresPermission("admin:alert:channel:view")
    @GetMapping("/record/page")
    public Result<Page<AlertRecord>> recordPage(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) Long ruleId) {
        QueryWrapper<AlertRecord> qw = new QueryWrapper<>();
        if (ruleId != null) {
            qw.eq("rule_id", ruleId);
        }
        qw.orderByDesc("fired_at");
        return Result.success(
                recordMapper.selectPage(new Page<>(PageQuery.pageNum(pageNum), PageQuery.pageSize(pageSize)), qw));
    }
}
