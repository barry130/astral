package com.astral.dao.mapper;

import com.astral.dao.entity.StatErrorLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 统计错误明细表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface StatErrorLogMapper extends BaseMapper<StatErrorLog> {

    /**
     * 按 fingerprint 分组的当日错误汇总（STATS_DESIGN.md §4.2 error/summary）
     * <p>
     * sampleMessage / topAppVersion 通过相关子查询取代表性值：
     * sampleMessage 取组内最近一条；topAppVersion 取组内出现最多的版本。
     * </p>
     */
    @Select("SELECT e.fingerprint AS \"fingerprint\", " +
            "COUNT(*) AS \"count\", " +
            "COUNT(DISTINCT e.device_id) AS \"affectedDevices\", " +
            "MIN(e.error_type) AS \"errorType\", " +
            "(SELECT e2.message FROM stat_error_log e2 WHERE e2.fingerprint = e.fingerprint " +
            "  ORDER BY e2.occur_time DESC LIMIT 1) AS \"sampleMessage\", " +
            "MIN(e.occur_time) AS \"firstSeen\", " +
            "MAX(e.occur_time) AS \"lastSeen\", " +
            "(SELECT e3.app_version FROM stat_error_log e3 WHERE e3.fingerprint = e.fingerprint " +
            "  GROUP BY e3.app_version ORDER BY COUNT(*) DESC LIMIT 1) AS \"topAppVersion\" " +
            "FROM stat_error_log e " +
            "WHERE e.occur_time >= #{start} AND e.occur_time < #{end} " +
            "GROUP BY e.fingerprint " +
            "ORDER BY \"count\" DESC")
    List<Map<String, Object>> selectErrorSummary(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);
}
