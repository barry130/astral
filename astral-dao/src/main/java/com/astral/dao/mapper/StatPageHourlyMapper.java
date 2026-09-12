package com.astral.dao.mapper;

import com.astral.dao.entity.StatPageHourly;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 统计页面小时桶 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface StatPageHourlyMapper extends BaseMapper<StatPageHourly> {

    /**
     * 页面 PV 原子累加（upsert 第二步：唯一键冲突时执行）
     */
    @Update("UPDATE stat_page_hourly SET pv = pv + #{pv} " +
            "WHERE bucket_hour = #{bucketHour} AND ut = #{ut} AND page = #{page}")
    int incrementPagePv(@Param("bucketHour") LocalDateTime bucketHour,
                        @Param("ut") String ut,
                        @Param("page") String page,
                        @Param("pv") long pv);
}
