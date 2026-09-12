package com.astral.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务开关配置（STATS_DESIGN.md §5.3）
 * <p>
 * 项目此前无 @EnableScheduling，此处开启以支持
 * StatAggregationJob（接口指标每分钟 flush + 过期数据每日清理）。
 * </p>
 */
@Configuration
@EnableScheduling
public class ScheduleConfig {
}
