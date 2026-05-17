package com.astral.dao.mapper;

import com.astral.dao.entity.SequenceConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 序列配置表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface SequenceConfigMapper extends BaseMapper<SequenceConfig> {

    /**
     * 根据业务键查询序列配置
     *
     * @param bizKey 业务键
     * @return 序列配置信息
     */
    @Select("SELECT * FROM sequence_config WHERE biz_key = #{bizKey}")
    SequenceConfig selectByBizKey(@Param("bizKey") String bizKey);
}
