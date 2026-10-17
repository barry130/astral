package com.astral.qt.mapper;

import com.astral.qt.entity.QtAppUpdate;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QtAppUpdateMapper extends BaseMapper<QtAppUpdate> {
    /** 保存平台或产物前锁定父行，保证覆盖校验与全量替换串行执行。 */
    @Select("SELECT * FROM qt_app_update WHERE id = #{id} FOR UPDATE")
    QtAppUpdate selectByIdForUpdate(@Param("id") Long id);
}