package com.astral.dao.mapper;

import com.astral.dao.entity.AlertRecord;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 告警触发记录 Mapper
 */
@Mapper
public interface AlertRecordMapper extends BaseMapper<AlertRecord> {
}
