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
     * 按 fingerprint 分组的当日错误汇总
     * <p>
     * sampleMessage / topAppVersion 通过相关子查询取代表性值：
     * sampleMessage 取组内最近一条；topAppVersion 取组内出现最多的版本。
     * </p>
     * <p>
     * 平台（ut）与版本（appVersion）为可选过滤，传空串或 null 表示不限制。
     * <b>两个相关子查询必须带上与主查询同一套条件（含时间范围）</b>，
     * 否则筛了平台/日期之后，样例信息与代表版本仍可能来自其它维度，出现"张冠李戴"。
     * </p>
     */
    @Select("<script>" +
            "SELECT e.fingerprint AS \"fingerprint\", " +
            "COUNT(*) AS \"count\", " +
            "COUNT(DISTINCT e.device_id) AS \"affectedDevices\", " +
            "MIN(e.error_type) AS \"errorType\", " +
            "(SELECT e2.message FROM stat_error_log e2 WHERE e2.fingerprint = e.fingerprint " +
            "AND e2.occur_time &gt;= #{start} AND e2.occur_time &lt; #{end} " +
            "<if test=\"source != null and source != ''\">AND e2.source = #{source} </if>" +
            "<if test=\"ut != null and ut != ''\">AND e2.ut = #{ut} </if>" +
            "<if test=\"appVersion != null and appVersion != ''\">AND e2.app_version = #{appVersion} </if>" +
            "ORDER BY e2.occur_time DESC LIMIT 1) AS \"sampleMessage\", " +
            "MIN(e.occur_time) AS \"firstSeen\", " +
            "MAX(e.occur_time) AS \"lastSeen\", " +
            "(SELECT e3.app_version FROM stat_error_log e3 WHERE e3.fingerprint = e.fingerprint " +
            "AND e3.occur_time &gt;= #{start} AND e3.occur_time &lt; #{end} " +
            "<if test=\"source != null and source != ''\">AND e3.source = #{source} </if>" +
            "<if test=\"ut != null and ut != ''\">AND e3.ut = #{ut} </if>" +
            "<if test=\"appVersion != null and appVersion != ''\">AND e3.app_version = #{appVersion} </if>" +
            "GROUP BY e3.app_version ORDER BY COUNT(*) DESC LIMIT 1) AS \"topAppVersion\" " +
            "FROM stat_error_log e " +
            "WHERE e.occur_time &gt;= #{start} AND e.occur_time &lt; #{end} " +
            "<if test=\"source != null and source != ''\">AND e.source = #{source} </if>" +
            "<if test=\"ut != null and ut != ''\">AND e.ut = #{ut} </if>" +
            "<if test=\"appVersion != null and appVersion != ''\">AND e.app_version = #{appVersion} </if>" +
            "GROUP BY e.fingerprint " +
            "ORDER BY \"count\" DESC" +
            "</script>")
    List<Map<String, Object>> selectErrorSummary(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 @Param("source") String source,
                                                 @Param("ut") String ut,
                                                 @Param("appVersion") String appVersion);
}
