package com.astral.dao.mapper;

import com.astral.dao.entity.OperateLog;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface OperateLogMapper extends BaseMapper<OperateLog> {
}
