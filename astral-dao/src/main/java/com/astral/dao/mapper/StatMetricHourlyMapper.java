package com.astral.dao.mapper;

import com.astral.dao.entity.StatMetricHourly;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 统计小时指标桶 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface StatMetricHourlyMapper extends BaseMapper<StatMetricHourly> {

    /**
     * 原子累加小时桶指标（upsert 第二步：唯一键冲突时执行）
     * <p>
     * 单条 SQL 行级原子更新，并发不丢计数；增量字段全量累加（0 值不改变结果）。
     * </p>
     */
    @Insert("UPDATE stat_metric_hourly SET pv = pv + #{pv}, visits = visits + #{visits}, " +
            "launches = launches + #{launches}, total_duration_ms = total_duration_ms + #{totalDurationMs}, " +
            "error_count = error_count + #{errorCount} " +
            "WHERE bucket_hour = #{bucketHour} AND ut = #{ut} AND app_version = #{appVersion}")
    int incrementMetric(@Param("bucketHour") LocalDateTime bucketHour,
                        @Param("ut") String ut,
                        @Param("appVersion") String appVersion,
                        @Param("pv") long pv,
                        @Param("visits") long visits,
                        @Param("launches") long launches,
                        @Param("totalDurationMs") long totalDurationMs,
                        @Param("errorCount") long errorCount);
}
