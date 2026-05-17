package com.astral.server.controller;

import com.astral.common.result.Result;
import com.astral.dao.entity.ApiStatistics;
import com.astral.server.service.ApiStatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "API统计")
@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class ApiStatisticsController {

    private final ApiStatisticsService apiStatisticsService;

    @Operation(summary = "按日期范围查询API统计")
    @GetMapping("/api")
    public Result<List<ApiStatistics>> getApiStatistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return Result.success(apiStatisticsService.getStatisticsByDateRange(startDate, endDate));
    }

    @Operation(summary = "Top N API调用排行")
    @GetMapping("/api/top")
    public Result<List<Map<String, Object>>> getTopApiStatistics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "10") int limit) {
        LocalDate queryDate = date != null ? date : LocalDate.now();
        List<ApiStatistics> statistics = apiStatisticsService.getTopApiStatistics(queryDate, limit);
        
        List<Map<String, Object>> result = statistics.stream()
                .map(s -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("apiPath", s.getApiPath());
                    map.put("apiMethod", s.getApiMethod());
                    map.put("callCount", s.getCallCount());
                    map.put("successCount", s.getSuccessCount());
                    map.put("failureCount", s.getFailureCount());
                    map.put("avgTime", s.getAvgTime());
                    map.put("maxTime", s.getMaxTime());
                    return map;
                })
                .collect(Collectors.toList());
        
        return Result.success(result);
    }
}
