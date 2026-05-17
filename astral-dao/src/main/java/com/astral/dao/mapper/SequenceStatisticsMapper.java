package com.astral.dao.mapper;

import com.astral.dao.entity.SequenceStatistics;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 序列统计表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface SequenceStatisticsMapper extends BaseMapper<SequenceStatistics> {

    /**
     * 根据业务键查询统计信息
     *
     * @param bizKey 业务键
     * @return 序列统计信息
     */
    @Select("SELECT * FROM sequence_statistics WHERE biz_key = #{bizKey}")
    SequenceStatistics selectByBizKey(@Param("bizKey") String bizKey);
}
