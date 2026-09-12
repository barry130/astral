package com.astral.monitor.service;

import com.astral.dao.entity.StatErrorLog;
import com.astral.dao.entity.StatDevice;
import com.astral.dao.entity.StatMetricHourly;
import com.astral.dao.mapper.StatApiHourlyMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import com.astral.dao.mapper.StatDeviceMapper;
import com.astral.dao.mapper.StatMetricHourlyMapper;
import com.astral.monitor.dto.ApiTopDTO;
import com.astral.monitor.dto.ApiTopResultDTO;
import com.astral.monitor.dto.ApiTopSummaryDTO;
import com.astral.monitor.dto.DeviceOverviewDTO;
import com.astral.monitor.dto.ErrorSummaryDTO;
import com.astral.monitor.dto.TrendDTO;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计报表查询服务（STATS_DESIGN.md §4.2 六个报表接口）
 * <p>
 * 口径：avgDurationMs = total_duration_ms / max(visits,1)；
 * trend 无数据小时补 0（长度恒 24）；api/top failure = status >= 400。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatReportService {

    /** trend 指标 → 列名白名单（防注入） */
    private static final Map<String, String> METRIC_COLUMNS = Map.of(
            "pv", "pv",
            "visits", "visits",
            "launches", "launches",
            "errorCount", "error_count");

    private final StatDeviceMapper statDeviceMapper;
    private final StatMetricHourlyMapper statMetricHourlyMapper;
    private final StatErrorLogMapper statErrorLogMapper;
    private final StatApiHourlyMapper statApiHourlyMapper;

    /**
     * 设备统计概览（今日 vs 昨日）
     */
    public Map<String, DeviceOverviewDTO> getOverview(LocalDate date, String ut) {
        Map<String, DeviceOverviewDTO> result = new HashMap<>(4);
        result.put("date", null);
        DeviceOverviewDTO today = buildOverview(date, ut);
        DeviceOverviewDTO yesterday = buildOverview(date.minusDays(1), ut);
        today.setDate(date.toString());
        yesterday.setDate(date.minusDays(1).toString());
        result.put("today", today);
        result.put("yesterday", yesterday);
        return result;
    }

    /**
     * 单日概览（设备维度走 stat_device，指标维度走 stat_metric_hourly）
     */
    private DeviceOverviewDTO buildOverview(LocalDate date, String ut) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        DeviceOverviewDTO dto = new DeviceOverviewDTO();
        dto.setNewDevices(statDeviceMapper.selectCount(deviceQuery(ut, w -> w.eq("first_date", date))));
        dto.setActiveDevices(statDeviceMapper.selectCount(deviceQuery(ut, w -> w.eq("last_date", date))));
        dto.setTotalDevices(statDeviceMapper.selectCount(deviceQuery(ut, w -> w.le("first_date", date))));

        QueryWrapper<StatMetricHourly> qw = new QueryWrapper<>();
        qw.select(
                "COALESCE(SUM(pv), 0) AS \"pv\"",
                "COALESCE(SUM(visits), 0) AS \"visits\"",
                "COALESCE(SUM(launches), 0) AS \"launches\"",
                "COALESCE(SUM(total_duration_ms), 0) AS \"totalDurationMs\"",
                "COALESCE(SUM(error_count), 0) AS \"errorCount\"");
        qw.ge("bucket_hour", start).lt("bucket_hour", end);
        if (!isAll(ut)) {
            qw.eq("ut", ut);
        }
        List<Map<String, Object>> rows = statMetricHourlyMapper.selectMaps(qw);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);

        long pv = toLong(row.get("pv"));
        long visits = toLong(row.get("visits"));
        long totalDurationMs = toLong(row.get("totalDurationMs"));
        dto.setPv(pv);
        dto.setVisits(visits);
        dto.setLaunches(toLong(row.get("launches")));
        dto.setErrorCount(toLong(row.get("errorCount")));
        dto.setAvgDurationMs(totalDurationMs / Math.max(visits, 1));
        return dto;
    }

    /**
     * 24 小时趋势（今日 vs 昨日，补零）
     */
    public TrendDTO getTrend(String metric, LocalDate date, String ut) {
        String column = METRIC_COLUMNS.get(metric);
        if (column == null) {
            throw new IllegalArgumentException("不支持的指标: " + metric);
        }
        TrendDTO dto = new TrendDTO();
        dto.setHours(TrendDTO.hourLabels());
        dto.setToday(hourlyValues(column, date, ut));
        dto.setYesterday(hourlyValues(column, date.minusDays(1), ut));
        return dto;
    }

    /**
     * 单日 24 点小时聚合（补零）
     */
    private List<Long> hourlyValues(String column, LocalDate date, String ut) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        QueryWrapper<StatMetricHourly> qw = new QueryWrapper<>();
        qw.select("EXTRACT(HOUR FROM bucket_hour) AS \"hour\"",
                        "COALESCE(SUM(" + column + "), 0) AS \"val\"")
                .ge("bucket_hour", start)
                .lt("bucket_hour", end)
                .groupBy("EXTRACT(HOUR FROM bucket_hour)");
        if (!isAll(ut)) {
            qw.eq("ut", ut);
        }
        List<Map<String, Object>> rows = statMetricHourlyMapper.selectMaps(qw);

        Map<Integer, Long> byHour = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int hour = toInt(row.get("hour"));
            byHour.put(hour, toLong(row.get("val")));
        }
        List<Long> values = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            values.add(byHour.getOrDefault(h, 0L));
        }
        return values;
    }

    /**
     * 接口调用 Top 榜（兼容旧 /api/v1/statistics/api/top 字段契约）
     * <p>
     * 返回 Top N 明细 {@code list} + 当天<b>全部</b>接口汇总 {@code summary}。
     * {@code summary} 与 limit 无关，供前端展示"总调用次数"等汇总卡片，
     * 避免 Top10 / Top20 显示不一致。
     * </p>
     */
    public ApiTopResultDTO getApiTop(LocalDate date, int limit) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<Map<String, Object>> rows = statApiHourlyMapper.selectApiTop(start, end, limit);
        List<ApiTopDTO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ApiTopDTO dto = new ApiTopDTO();
            dto.setApiPath((String) row.get("apiPath"));
            dto.setApiMethod((String) row.get("apiMethod"));
            dto.setCallCount(toLong(row.get("callCount")));
            dto.setSuccessCount(toLong(row.get("successCount")));
            dto.setFailureCount(toLong(row.get("failureCount")));
            dto.setAvgTime(toLong(row.get("avgTime")));
            dto.setMaxTime(toLong(row.get("maxTime")));
            list.add(dto);
        }

        Map<String, Object> summaryRow = statApiHourlyMapper.selectApiSummary(start, end);
        if (summaryRow == null) {
            summaryRow = Map.of();
        }
        long callCount = toLong(summaryRow.get("callCount"));
        long successCount = toLong(summaryRow.get("successCount"));
        long failureCount = toLong(summaryRow.get("failureCount"));
        ApiTopSummaryDTO summary = new ApiTopSummaryDTO(callCount, successCount, failureCount);

        return new ApiTopResultDTO(list, summary);
    }

    /**
     * 单接口 24 小时调用趋势（callCount 与 avgMs，补零）
     */
    public Map<String, List<Long>> getApiTrend(String uri, String method, LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<Map<String, Object>> rows = statApiHourlyMapper.selectApiTrend(uri, method, start, end);

        Map<Integer, Map<String, Long>> byHour = new HashMap<>();
        for (Map<String, Object> row : rows) {
            int hour = toInt(row.get("hour"));
            byHour.put(hour, Map.of(
                    "callCount", toLong(row.get("callCount")),
                    "avgMs", toLong(row.get("avgMs"))));
        }
        List<Long> callCounts = new ArrayList<>(24);
        List<Long> avgMs = new ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            Map<String, Long> v = byHour.get(h);
            callCounts.add(v != null ? v.get("callCount") : 0L);
            avgMs.add(v != null ? v.get("avgMs") : 0L);
        }
        return Map.of("callCount", callCounts, "avgMs", avgMs);
    }

    /**
     * 错误明细分页
     */
    public Page<StatErrorLog> getErrorPage(long pageNum, long pageSize, String errorType,
                                           String appVersion, String fingerprint) {
        QueryWrapper<StatErrorLog> qw = new QueryWrapper<>();
        if (errorType != null && !errorType.isBlank()) {
            qw.eq("error_type", errorType);
        }
        if (appVersion != null && !appVersion.isBlank()) {
            qw.eq("app_version", appVersion);
        }
        if (fingerprint != null && !fingerprint.isBlank()) {
            qw.eq("fingerprint", fingerprint);
        }
        qw.orderByDesc("occur_time");
        return statErrorLogMapper.selectPage(new Page<>(pageNum, pageSize), qw);
    }

    /**
     * 错误分组汇总（按 fingerprint）
     */
    public List<ErrorSummaryDTO> getErrorSummary(LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();
        List<Map<String, Object>> rows = statErrorLogMapper.selectErrorSummary(start, end);
        List<ErrorSummaryDTO> list = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            ErrorSummaryDTO dto = new ErrorSummaryDTO();
            dto.setFingerprint((String) row.get("fingerprint"));
            dto.setCount(toLong(row.get("count")));
            dto.setAffectedDevices(toLong(row.get("affectedDevices")));
            dto.setErrorType((String) row.get("errorType"));
            dto.setSampleMessage((String) row.get("sampleMessage"));
            dto.setFirstSeen(toDateTime(row.get("firstSeen")));
            dto.setLastSeen(toDateTime(row.get("lastSeen")));
            dto.setTopAppVersion((String) row.get("topAppVersion"));
            list.add(dto);
        }
        return list;
    }

    /**
     * 设备统计查询（ut=all 不加平台过滤）
     */
    private QueryWrapper<StatDevice> deviceQuery(String ut, java.util.function.Consumer<QueryWrapper<StatDevice>> filter) {
        QueryWrapper<StatDevice> qw = new QueryWrapper<>();
        filter.accept(qw);
        if (!isAll(ut)) {
            qw.eq("ut", ut);
        }
        return qw;
    }

    private static boolean isAll(String ut) {
        return ut == null || ut.isBlank() || "all".equalsIgnoreCase(ut);
    }

    private static long toLong(Object v) {
        if (v == null) {
            return 0L;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return new java.math.BigDecimal(v.toString()).intValue();
        } catch (Exception e) {
            return 0;
        }
    }

    private static LocalDateTime toDateTime(Object v) {
        if (v instanceof LocalDateTime dt) {
            return dt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        return null;
    }
}
