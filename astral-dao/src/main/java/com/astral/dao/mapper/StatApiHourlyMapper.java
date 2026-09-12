package com.astral.dao.mapper;

import com.astral.dao.entity.StatApiHourly;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统计接口小时桶 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface StatApiHourlyMapper extends BaseMapper<StatApiHourly> {

    /**
     * 接口指标原子累加（upsert 第二步：唯一键冲突时执行）
     */
    @Update("UPDATE stat_api_hourly SET call_count = call_count + #{callCount}, " +
            "sum_ms = sum_ms + #{sumMs}, max_ms = GREATEST(max_ms, #{maxMs}) " +
            "WHERE bucket_hour = #{bucketHour} AND uri = #{uri} AND method = #{method} AND status = #{status}")
    int incrementApi(@Param("bucketHour") LocalDateTime bucketHour,
                     @Param("uri") String uri,
                     @Param("method") String method,
                     @Param("status") Integer status,
                     @Param("callCount") long callCount,
                     @Param("sumMs") long sumMs,
                     @Param("maxMs") int maxMs);

    /**
     * 按日聚合接口 Top 榜（uri+method 维度，callCount 降序）
     * <p>
     * failure = status >= 400；avgTime = sum_ms / call_count。
     * </p>
     */
    @Select("SELECT uri AS \"apiPath\", method AS \"apiMethod\", " +
            "SUM(call_count) AS \"callCount\", " +
            "SUM(CASE WHEN status < 400 THEN call_count ELSE 0 END) AS \"successCount\", " +
            "SUM(CASE WHEN status >= 400 THEN call_count ELSE 0 END) AS \"failureCount\", " +
            "(SUM(sum_ms) / GREATEST(SUM(call_count), 1)) AS \"avgTime\", " +
            "MAX(max_ms) AS \"maxTime\" " +
            "FROM stat_api_hourly " +
            "WHERE bucket_hour >= #{start} AND bucket_hour < #{end} " +
            "GROUP BY uri, method " +
            "ORDER BY \"callCount\" DESC " +
            "LIMIT #{limit}")
    List<Map<String, Object>> selectApiTop(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end,
                                           @Param("limit") int limit);

    /**
     * 按日聚合所有接口的全量汇总（不分组，不计入 Top limit）
     * <p>
     * 返回当天全部接口的总调用/成功/失败次数，供前端汇总卡片使用（与 Top N 无关）。
     * </p>
     */
    @Select("SELECT " +
            "COALESCE(SUM(call_count), 0) AS \"callCount\", " +
            "COALESCE(SUM(CASE WHEN status < 400 THEN call_count ELSE 0 END), 0) AS \"successCount\", " +
            "COALESCE(SUM(CASE WHEN status >= 400 THEN call_count ELSE 0 END), 0) AS \"failureCount\" " +
            "FROM stat_api_hourly " +
            "WHERE bucket_hour >= #{start} AND bucket_hour < #{end}")
    Map<String, Object> selectApiSummary(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /**
     * 按日按接口聚合 24 小时趋势（callCount 与 avgMs）
     */
    @Select("SELECT EXTRACT(HOUR FROM bucket_hour) AS \"hour\", " +
            "SUM(call_count) AS \"callCount\", " +
            "(SUM(sum_ms) / GREATEST(SUM(call_count), 1)) AS \"avgMs\" " +
            "FROM stat_api_hourly " +
            "WHERE bucket_hour >= #{start} AND bucket_hour < #{end} " +
            "AND uri = #{uri} AND method = #{method} " +
            "GROUP BY EXTRACT(HOUR FROM bucket_hour) " +
            "ORDER BY \"hour\"")
    List<Map<String, Object>> selectApiTrend(@Param("uri") String uri,
                                             @Param("method") String method,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);
}
