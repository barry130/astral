package com.astral.monitor.alert;

import com.astral.dao.entity.AlertChannel;
import com.astral.dao.entity.AlertRecord;
import com.astral.dao.entity.AlertRule;
import com.astral.dao.entity.StatErrorLog;
import com.astral.dao.mapper.AlertChannelMapper;
import com.astral.dao.mapper.AlertRecordMapper;
import com.astral.dao.mapper.AlertRuleMapper;
import com.astral.dao.mapper.StatApiHourlyMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import com.astral.system.mail.MailService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 告警引擎：每分钟评估全部启用的告警规则，阈值命中即向绑定渠道发通知。
 *
 * <p>三类指标（{@code sys_alert_rule.metric}，大小写不敏感）：</p>
 * <ul>
 *   <li>{@code SERVER_ERROR_COUNT}：窗口内服务端 500 登记条数（stat_error_log.source=server，
 *       由 GlobalExceptionHandler 兜底登记）</li>
 *   <li>{@code CLIENT_ERROR_COUNT}：窗口内客户端上报错误条数（stat_error_log.source=client）</li>
 *   <li>{@code HTTP_5XX_COUNT}：窗口内 5xx 请求数（stat_api_hourly.status&gt;=500 求和）</li>
 * </ul>
 *
 * <p>渠道两种：EMAIL（复用 MailService 直发，不走插件授权/额度）与
 * WEBHOOK（JDK HttpClient POST JSON，可选自定义请求头密钥，钉钉/飞书自定义机器人
 * 可用各自的加密/签名方式时再扩展 format 字段）。</p>
 *
 * <p>冷却：规则级 {@code cooldown_minutes}，用 last_fired_at 判断；触发记录
 * 全量落 {@code sys_alert_record}（含发送失败，便于排障）。评估全程逐规则隔离，
 * 单规则/渠道故障不影响其余。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "astral.stat.enabled", havingValue = "true", matchIfMissing = true)
public class AlertEngine {

    public static final String METRIC_SERVER_ERROR = "SERVER_ERROR_COUNT";
    public static final String METRIC_CLIENT_ERROR = "CLIENT_ERROR_COUNT";
    public static final String METRIC_HTTP_5XX = "HTTP_5XX_COUNT";
    public static final Set<String> SUPPORTED_METRICS = Set.of(
            METRIC_SERVER_ERROR, METRIC_CLIENT_ERROR, METRIC_HTTP_5XX);

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_WEBHOOK = "WEBHOOK";

    private final AlertRuleMapper alertRuleMapper;
    private final AlertChannelMapper alertChannelMapper;
    private final AlertRecordMapper alertRecordMapper;
    private final StatErrorLogMapper statErrorLogMapper;
    private final StatApiHourlyMapper statApiHourlyMapper;
    /** 可选：astral-mail 属 system 模块，未装配时 EMAIL 渠道降级记 FAIL */
    private final ObjectProvider<MailService> mailServiceProvider;

    /** 渠道配置解析器（JSON 容错），引擎与测试接口共用 */
    private final AlertConfigParser configParser = new AlertConfigParser();

    /** WEBHOOK 发送复用 HttpClient（进程内共享） */
    private final java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** 规则评估互斥锁：fixedDelay 串行本来不会重入，防御手动触发与定时并发 */
    private final Object evaluateLock = new Object();

    /**
     * 每分钟评估一次（独立调度，不与指标 flush 耦合；initialDelay 错开以避让启动高峰）
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void evaluate() {
        synchronized (evaluateLock) {
            List<AlertRule> rules;
            try {
                rules = alertRuleMapper.selectList(new QueryWrapper<AlertRule>().eq("enabled", 1));
            } catch (Exception e) {
                log.warn("[alert] 规则读取失败，本轮跳过: {}", e.getMessage());
                return;
            }
            for (AlertRule rule : rules) {
                try {
                    evaluateOne(rule);
                } catch (Exception e) {
                    log.warn("[alert] 规则[{}]评估失败: {}", rule.getName(), e.getMessage());
                }
            }
        }
    }

    private void evaluateOne(AlertRule rule) {
        if (!SUPPORTED_METRICS.contains(normalizeMetric(rule.getMetric()))) {
            return;
        }
        // 冷却检查
        if (rule.getLastFiredAt() != null && rule.getCooldownMinutes() != null && rule.getCooldownMinutes() > 0) {
            LocalDateTime cooldownUntil = rule.getLastFiredAt().plusMinutes(rule.getCooldownMinutes());
            if (LocalDateTime.now().isBefore(cooldownUntil)) {
                return;
            }
        }
        int windowMinutes = rule.getWindowMinutes() != null && rule.getWindowMinutes() > 0
                ? rule.getWindowMinutes() : 5;
        long value = queryMetric(normalizeMetric(rule.getMetric()), windowMinutes);
        long threshold = rule.getThreshold() != null ? rule.getThreshold() : 0;
        if (value < threshold) {
            return;
        }
        fire(rule, value, windowMinutes);
    }

    /** 查询窗口内指标值 */
    public long queryMetric(String metric, int windowMinutes) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusMinutes(windowMinutes);
        return switch (metric) {
            case METRIC_SERVER_ERROR -> countErrors("server", start);
            case METRIC_CLIENT_ERROR -> countErrors("client", start);
            case METRIC_HTTP_5XX -> count5xx(start);
            default -> 0L;
        };
    }

    private long countErrors(String source, LocalDateTime start) {
        QueryWrapper<StatErrorLog> qw = new QueryWrapper<>();
        qw.eq("source", source).ge("occur_time", start);
        Long count = statErrorLogMapper.selectCount(qw);
        return count == null ? 0L : count;
    }

    private long count5xx(LocalDateTime start) {
        Long count = statApiHourlyMapper.sumCallsByStatusRange(start, LocalDateTime.now(), 500, 599);
        return count == null ? 0L : count;
    }

    /** 触发：发通知 + 落记录 + 更新 last_fired_at */
    private void fire(AlertRule rule, long value, int windowMinutes) {
        AlertChannel channel = alertChannelMapper.selectById(rule.getChannelId());
        String title = String.format("[Astral 告警] %s", rule.getName());
        String content = String.format(
                "规则：%s%n指标：%s%n当前值：%d（阈值 %d，窗口 %d 分钟）%n时间：%s",
                rule.getName(), normalizeMetric(rule.getMetric()), value, rule.getThreshold(),
                windowMinutes, LocalDateTime.now());

        boolean ok = false;
        String errorMsg = null;
        if (channel == null) {
            errorMsg = "绑定渠道不存在（id=" + rule.getChannelId() + "）";
        } else {
            try {
                deliver(channel, title, content);
                ok = true;
            } catch (Exception e) {
                errorMsg = e.getMessage();
                log.warn("[alert] 渠道[{}]发送失败: {}", channel.getName(), e.getMessage());
            }
        }

        // 记录
        AlertRecord record = new AlertRecord();
        record.setRuleId(rule.getId());
        record.setRuleName(rule.getName());
        record.setChannelId(channel == null ? null : channel.getId());
        record.setChannelName(channel == null ? null : channel.getName());
        record.setTitle(title);
        record.setContent(content);
        record.setMetricValue(value);
        record.setStatus(ok ? "SUCCESS" : "FAIL");
        record.setErrorMsg(errorMsg);
        record.setFiredAt(LocalDateTime.now());
        try {
            alertRecordMapper.insert(record);
        } catch (Exception e) {
            log.warn("[alert] 触发记录落库失败: {}", e.getMessage());
        }

        // 更新冷却锚点（无论发送成败，避免失败渠道每分钟重试轰炸）
        rule.setLastFiredAt(LocalDateTime.now());
        try {
            alertRuleMapper.updateById(rule);
        } catch (Exception e) {
            log.warn("[alert] 规则冷却时间更新失败: {}", e.getMessage());
        }
        log.info("[alert] 规则[{}]触发：value={} threshold={} channel={} ok={}",
                rule.getName(), value, rule.getThreshold(),
                channel == null ? "无" : channel.getName(), ok);
    }

    /** 按渠道类型分发发送 */
    public void deliver(AlertChannel channel, String title, String content) throws Exception {
        String type = channel.getType() == null ? "" : channel.getType().toUpperCase();
        Map<String, String> config = configParser.parse(channel.getConfig());
        switch (type) {
            case CHANNEL_EMAIL -> deliverEmail(config, title, content);
            case CHANNEL_WEBHOOK -> deliverWebhook(config, title, content);
            default -> throw new IllegalArgumentException("不支持的渠道类型：" + channel.getType());
        }
    }

    private void deliverEmail(Map<String, String> config, String title, String content) {
        String to = config.get("to");
        if (to == null || to.isBlank()) {
            throw new IllegalArgumentException("EMAIL 渠道缺少 to 收件地址");
        }
        MailService mailService = mailServiceProvider.getIfAvailable();
        if (mailService == null) {
            throw new IllegalStateException("邮件服务不可用");
        }
        mailService.sendSystemAlert(to.trim(), title, content);
    }

    private void deliverWebhook(Map<String, String> config, String title, String content) throws Exception {
        String url = config.get("url");
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("WEBHOOK 渠道缺少 url");
        }
        StringBuilder json = new StringBuilder();
        json.append("{\"title\":").append(jsonEscape(title))
                .append(",\"content\":").append(jsonEscape(content))
                .append(",\"source\":\"astral-alert\"}");
        java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url.trim()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.toString()));
        String headerName = config.getOrDefault("header", "X-Astral-Alert");
        String secret = config.get("secret");
        if (secret != null && !secret.isBlank()) {
            builder.header(headerName, secret);
        }
        java.net.http.HttpResponse<String> response =
                httpClient.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Webhook 响应 " + response.statusCode());
        }
    }

    private static String jsonEscape(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String normalizeMetric(String metric) {
        return metric == null ? "" : metric.trim().toUpperCase();
    }

    public AlertConfigParser getConfigParser() {
        return configParser;
    }
}
