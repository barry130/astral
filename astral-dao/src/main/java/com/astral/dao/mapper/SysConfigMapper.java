package com.astral.dao.mapper;

import com.astral.dao.entity.SysConfig;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
