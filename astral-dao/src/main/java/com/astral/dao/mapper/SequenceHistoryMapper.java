package com.astral.dao.mapper;

import com.astral.dao.entity.SequenceHistory;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 序列生成历史表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface SequenceHistoryMapper extends BaseMapper<SequenceHistory> {
}
