package com.astral.server.service;

import com.astral.dao.entity.ApiStatistics;
import com.astral.dao.mapper.ApiStatisticsMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApiStatisticsService {

    private final ApiStatisticsMapper apiStatisticsMapper;

    @Async("sequenceAsyncExecutor")
    public void recordApiCall(String apiPath, String apiMethod, boolean success, long executeTime) {
        try {
            LocalDate today = LocalDate.now();
            
            QueryWrapper<ApiStatistics> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("api_path", apiPath)
                       .eq("api_method", apiMethod)
                       .eq("stat_date", today);
            
            ApiStatistics statistics = apiStatisticsMapper.selectOne(queryWrapper);
            
            if (statistics == null) {
                statistics = new ApiStatistics();
                statistics.setApiPath(apiPath);
                statistics.setApiMethod(apiMethod);
                statistics.setStatDate(today);
                statistics.setCallCount(1L);
                statistics.setSuccessCount(success ? 1L : 0L);
                statistics.setFailureCount(success ? 0L : 1L);
                statistics.setTotalTime(executeTime);
                statistics.setAvgTime(executeTime);
                statistics.setMaxTime(executeTime);
                apiStatisticsMapper.insert(statistics);
            } else {
                statistics.setCallCount(statistics.getCallCount() + 1);
                if (success) {
                    statistics.setSuccessCount(statistics.getSuccessCount() + 1);
                } else {
                    statistics.setFailureCount(statistics.getFailureCount() + 1);
                }
                statistics.setTotalTime(statistics.getTotalTime() + executeTime);
                statistics.setAvgTime(statistics.getTotalTime() / statistics.getCallCount());
                if (executeTime > statistics.getMaxTime()) {
                    statistics.setMaxTime(executeTime);
                }
                apiStatisticsMapper.updateById(statistics);
            }
        } catch (Exception e) {
            log.error("记录API统计信息失败: {}", e.getMessage(), e);
        }
    }

    public List<ApiStatistics> getStatisticsByDateRange(LocalDate startDate, LocalDate endDate) {
        QueryWrapper<ApiStatistics> queryWrapper = new QueryWrapper<>();
        queryWrapper.between("stat_date", startDate, endDate)
                   .orderByDesc("call_count");
        return apiStatisticsMapper.selectList(queryWrapper);
    }

    public List<ApiStatistics> getTopApiStatistics(LocalDate date, int limit) {
        QueryWrapper<ApiStatistics> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("stat_date", date)
                   .orderByDesc("call_count")
                   .last("LIMIT " + limit);
        return apiStatisticsMapper.selectList(queryWrapper);
    }
}
