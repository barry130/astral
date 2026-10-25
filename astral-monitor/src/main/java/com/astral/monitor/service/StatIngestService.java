package com.astral.monitor.service;

import com.astral.dao.entity.StatDevice;
import com.astral.dao.mapper.StatDeviceMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import com.astral.dao.mapper.StatMetricHourlyMapper;
import com.astral.dao.mapper.StatPageHourlyMapper;
import com.astral.common.util.ClientHeaders;
import com.astral.monitor.dto.StatEventDTO;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

/**
 * 统计数据接入服务
 * <p>
 * 批量处理上报事件：设备 upsert、小时桶累加、错误明细落库。
 * 按 §5.4 约定采用"先 UPDATE 后 INSERT"的两步 upsert，避免唯一键竞态；
 * UPDATE 行级原子累加保证并发不丢计数。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatIngestService {

    /**
     * 统计时区。系统时区在不同宿主上不一致（容器常为 UTC，服务器为 Asia/Shanghai），
     * 小时桶边界会整体错位 8 小时，报表与业务口径永远对不上。可用
     * {@code astral.stat.zone}（环境变量 ASTRAL_STAT_ZONE）覆盖。
     */
    private static final ZoneId ZONE = ZoneId.of(System.getProperty("astral.stat.zone", "Asia/Shanghai"));

    /**
     * 客户端时间戳允许的偏离窗口（毫秒）。
     * <p>匿名上报的 {@code ts} 由客户端自报，不裁剪就能写进任意历史/未来小时的桶，
     * 污染任意日期的统计（错误日志的 {@code occurTime} 也会被随意摆布）。</p>
     */
    private static final long TS_MAX_SKEW_MS = 7L * 24 * 3600 * 1000;

    /** 匿名上报批次的并发准入上限（见 {@link #processBatchAsync}） */
    private static final int MAX_CONCURRENT_BATCHES =
            Integer.getInteger("astral.stat.max-concurrent-batches", 4);

    /** 匿名上报批次并发信号量：满即拒绝（不排队，见 processBatchAsync） */
    private static final java.util.concurrent.Semaphore IN_FLIGHT =
            new java.util.concurrent.Semaphore(MAX_CONCURRENT_BATCHES);

    /** 错误堆栈最大长度（16KB，超限截断） */
    private static final int MAX_STACK_LENGTH = 16 * 1024;

    /** 合法事件类型 */
    private static final Set<String> VALID_EVENTS = Set.of("launcher", "show", "hide", "page", "error", "custom");

    private final StatDeviceMapper statDeviceMapper;
    private final StatMetricHourlyMapper statMetricHourlyMapper;
    private final StatPageHourlyMapper statPageHourlyMapper;
    private final StatErrorLogMapper statErrorLogMapper;

    /**
     * 异步批量处理上报（复用全局 sequenceAsyncExecutor，见 §5.4.4；
     * 处理失败仅记日志，不影响客户端——客户端自身失败即回队列重试）
     *
     * @param events 事件列表
     * @param ip     服务端解析的客户端 IP（X-Forwarded-For 优先，可为 null）
     */
    @org.springframework.scheduling.annotation.Async("sequenceAsyncExecutor")
    public void processBatchAsync(List<StatEventDTO> events, String ip) {
        // 匿名入口 + 单请求最多 200 事件 × 每事件 2~3 条 SQL：不限并发时几个请求就能
        // 占满生产 Hikari 连接池（pool=5），表现是全站接口一起超时。
        // 这里做「准入」而不是阻塞排队：排队只会把虚拟线程也堆起来，
        // 丢一批匿名统计远比拖垮业务接口划算（客户端失败即回队列重试，不丢数据）。
        if (!IN_FLIGHT.tryAcquire()) {
            log.warn("[stat] 并发上报已满（{}），丢弃本批 total={} ip={}",
                    MAX_CONCURRENT_BATCHES, events.size(), ip);
            return;
        }
        try {
            int ok = processBatch(events, ip);
            log.debug("[stat] 批量上报处理完成 total={} ok={}", events.size(), ok);
        } catch (Exception e) {
            log.warn("[stat] 批量上报处理异常: {}", e.getMessage());
        } finally {
            IN_FLIGHT.release();
        }
    }

    /**
     * 批量处理上报事件（逐事件独立处理，单事件失败不影响其余）
     *
     * @param events 事件列表
     * @param ip     服务端解析的客户端 IP（可为 null）
     * @return 成功处理的事件数
     */
    public int processBatch(List<StatEventDTO> events, String ip) {
        int ok = 0;
        for (StatEventDTO event : events) {
            try {
                if (processOne(event, ip)) {
                    ok++;
                }
            } catch (Exception e) {
                log.warn("[stat] 事件处理失败 evt={} deviceId={}: {}", event.getEvt(), event.getDeviceId(), e.getMessage());
            }
        }
        return ok;
    }

    /**
     * 处理单个事件（按 §4.1 处理规则分发）
     */
    private boolean processOne(StatEventDTO event, String ip) {
        if (event.getEvt() == null || !VALID_EVENTS.contains(event.getEvt())) {
            log.warn("[stat] 非法事件类型丢弃: {}", event.getEvt());
            return false;
        }
        if (event.getUt() == null || event.getUt().isBlank()) {
            log.warn("[stat] 缺少平台字段丢弃: {}", event.getEvt());
            return false;
        }

        LocalDateTime eventTime = toLocalDateTime(event.getTs());
        // ut / appVersion 都是客户端自报且直接参与分组与唯一键：
        // 只截断不校验形态时，每次请求换个随机串就能持续制造新分组（保留 180/90 天）。
        // 一律走 ClientHeaders 的白名单/形态校验（与接口指标采集同一口径）。
        String ut = ClientHeaders.normalizeUt(event.getUt());
        String appVersion = ClientHeaders.normalizeVersion(event.getAppVersion());
        if (ut.isEmpty() && !event.getUt().isBlank()) {
            log.warn("[stat] 平台标识不在白名单，归并为未知: {}", event.getUt());
        }
        event = withNormalizedDimensions(event, ut, appVersion);
        switch (event.getEvt()) {
            case "launcher" -> {
                upsertDevice(event, eventTime, ip);
                incrementMetric(event, eventTime, 0L, 1L, 1L, 0L, 0L);
            }
            case "show" -> upsertDevice(event, eventTime, ip);
            case "hide" -> {
                long duration = event.getDuration() != null && event.getDuration() > 0 ? event.getDuration() : 0L;
                incrementMetric(event, eventTime, 0L, 0L, 0L, duration, 0L);
            }
            case "page" -> {
                if (isBlank(event.getPage())) {
                    log.warn("[stat] page 事件缺少页面路由丢弃");
                    return false;
                }
                incrementMetric(event, eventTime, 1L, 0L, 0L, 0L, 0L);
                incrementPagePv(event, eventTime);
            }
            case "error" -> {
                if (isBlank(event.getMessage())) {
                    log.warn("[stat] error 事件缺少 message 丢弃");
                    return false;
                }
                incrementMetric(event, eventTime, 0L, 0L, 0L, 0L, 1L);
                insertErrorLog(event, eventTime, ip);
            }
            case "custom" -> {
                // 本期不聚合，仅预留（extra 透传，见设计文档 §4.1）
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * 设备表 upsert（先 UPDATE 后 INSERT，唯一键 device_id）
     */
    private void upsertDevice(StatEventDTO event, LocalDateTime eventTime, String ip) {
        LocalDate today = eventTime.toLocalDate();
        int updated = statDeviceMapper.updateLastActive(
                event.getDeviceId(), event.getUt(),
                event.getAppVersion(), event.getModel(), event.getOs(),
                ip, today, eventTime);
        if (updated == 0) {
            StatDevice device = new StatDevice();
            device.setDeviceId(event.getDeviceId());
            device.setUt(event.getUt());
            device.setAppVersion(event.getAppVersion());
            device.setModel(event.getModel());
            device.setOs(event.getOs());
            device.setLastIp(ip);
            device.setFirstDate(today);
            device.setLastDate(today);
            device.setLastActiveTime(eventTime);
            try {
                statDeviceMapper.insert(device);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首报：已由其他线程插入，忽略
            }
        }
    }

    /**
     * 小时指标桶累加（先 UPDATE 后 INSERT，唯一键 bucket_hour+ut+app_version）
     */
    private void incrementMetric(StatEventDTO event, LocalDateTime eventTime,
                                 Long pv, Long visits, Long launches, Long durationMs, Long errorCount) {
        LocalDateTime bucketHour = toBucketHour(eventTime);
        String appVersion = event.getAppVersion() != null ? event.getAppVersion() : "";
        int updated = statMetricHourlyMapper.incrementMetric(
                bucketHour, event.getUt(), appVersion,
                pv, visits, launches, durationMs, errorCount);
        if (updated == 0) {
            com.astral.dao.entity.StatMetricHourly bucket = new com.astral.dao.entity.StatMetricHourly();
            bucket.setBucketHour(bucketHour);
            bucket.setUt(event.getUt());
            bucket.setAppVersion(appVersion);
            bucket.setPv(pv);
            bucket.setVisits(visits);
            bucket.setLaunches(launches);
            bucket.setTotalDurationMs(durationMs);
            bucket.setErrorCount(errorCount);
            try {
                statMetricHourlyMapper.insert(bucket);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首报：已由其他线程建桶，补一次累加
                statMetricHourlyMapper.incrementMetric(bucketHour, event.getUt(), appVersion,
                        pv, visits, launches, durationMs, errorCount);
            }
        }
    }

    /**
     * 页面小时桶 PV 累加（先 UPDATE 后 INSERT，唯一键 bucket_hour+ut+page）
     */
    private void incrementPagePv(StatEventDTO event, LocalDateTime eventTime) {
        LocalDateTime bucketHour = toBucketHour(eventTime);
        int updated = statPageHourlyMapper.incrementPagePv(bucketHour, event.getUt(), event.getPage(), 1L);
        if (updated == 0) {
            com.astral.dao.entity.StatPageHourly bucket = new com.astral.dao.entity.StatPageHourly();
            bucket.setBucketHour(bucketHour);
            bucket.setUt(event.getUt());
            bucket.setPage(event.getPage());
            bucket.setPv(1L);
            try {
                statPageHourlyMapper.insert(bucket);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 并发首报：已由其他线程建桶，补一次累加
                statPageHourlyMapper.incrementPagePv(bucketHour, event.getUt(), event.getPage(), 1L);
            }
        }
    }

    /**
     * 错误明细落库（fingerprint = MD5(type + message + 首行栈)）
     */
    private void insertErrorLog(StatEventDTO event, LocalDateTime eventTime, String ip) {
        String stack = event.getStack();
        if (stack != null && stack.length() > MAX_STACK_LENGTH) {
            stack = stack.substring(0, MAX_STACK_LENGTH);
        }
        String fingerprint = computeFingerprint(event.getErrorType(), event.getMessage(), stack);

        com.astral.dao.entity.StatErrorLog errorLog = new com.astral.dao.entity.StatErrorLog();
        errorLog.setFingerprint(fingerprint);
        errorLog.setSource("client");
        errorLog.setErrorType(event.getErrorType() != null ? event.getErrorType() : "js");
        errorLog.setMessage(truncate(event.getMessage(), 1024));
        errorLog.setStack(stack);
        errorLog.setPage(event.getPage());
        errorLog.setUt(event.getUt());
        errorLog.setAppVersion(event.getAppVersion());
        errorLog.setOs(event.getOs());
        errorLog.setModel(event.getModel());
        errorLog.setDeviceId(event.getDeviceId());
        errorLog.setRelease(event.getRelease());
        errorLog.setIp(ip);
        errorLog.setOccurTime(eventTime);
        statErrorLogMapper.insert(errorLog);
    }

    /**
     * 错误指纹：MD5(errorType + message + 堆栈首行)
     */
    private String computeFingerprint(String errorType, String message, String stack) {
        String firstStackLine = "";
        if (stack != null && !stack.isBlank()) {
            firstStackLine = stack.strip().split("\n", 2)[0];
        }
        String raw = (errorType == null ? "" : errorType) + "|" + (message == null ? "" : message) + "|" + firstStackLine;
        return DigestUtil.md5Hex(raw);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 用归一后的平台/版本重建事件（其余字段原样保留）。
     * <p>不就地改调用方对象：那是请求体，就地改会掩盖原始值（日志里看不到客户端真正发了什么）。</p>
     */
    private static StatEventDTO withNormalizedDimensions(StatEventDTO event, String ut, String appVersion) {
        StatEventDTO normalized = new StatEventDTO();
        normalized.setEvt(event.getEvt());
        normalized.setTs(event.getTs());
        normalized.setDeviceId(event.getDeviceId());
        normalized.setUt(ut);
        normalized.setAppVersion(appVersion);
        normalized.setModel(event.getModel());
        normalized.setOs(event.getOs());
        normalized.setPage(event.getPage());
        normalized.setDuration(event.getDuration());
        normalized.setCh(event.getCh());
        normalized.setErrorType(event.getErrorType());
        normalized.setMessage(event.getMessage());
        normalized.setStack(event.getStack());
        normalized.setRelease(event.getRelease());
        normalized.setExtra(event.getExtra());
        return normalized;
    }

    /**
     * 毫秒时间戳 → LocalDateTime（统计时区）。
     * <p>偏离当前时间超过 {@link #TS_MAX_SKEW_MS} 的「未来 / 远古」值一律按服务端当前时间处理：
     * 既不让伪造值污染历史桶，也不让它们落进未来桶（未来桶不会再被真实数据覆盖）。</p>
     */
    private static LocalDateTime toLocalDateTime(Long epochMs) {
        long now = System.currentTimeMillis();
        if (epochMs == null || epochMs <= 0 || Math.abs(epochMs - now) > TS_MAX_SKEW_MS) {
            return LocalDateTime.now(ZONE);
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZONE);
    }

    /**
     * 事件时间 → 整点小时桶（分钟/秒/纳秒清零）
     */
    private static LocalDateTime toBucketHour(LocalDateTime time) {
        return time.withMinute(0).withSecond(0).withNano(0);
    }
}
