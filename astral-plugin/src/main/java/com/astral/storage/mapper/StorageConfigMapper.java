package com.astral.storage.mapper;

import com.astral.storage.entity.StorageConfigEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * sys_storage_config Mapper
 */
@Mapper
public interface StorageConfigMapper extends BaseMapper<StorageConfigEntity> {
    @Select("SELECT * FROM sys_storage_config WHERE id = #{id} FOR UPDATE")
    @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
    StorageConfigEntity lockForSend(@Param("id") long id);
}
