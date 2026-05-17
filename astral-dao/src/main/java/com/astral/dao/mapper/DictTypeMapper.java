package com.astral.dao.mapper;

import com.astral.dao.entity.DictType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 数据字典类型表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface DictTypeMapper extends BaseMapper<DictType> {
}
