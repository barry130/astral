package com.astral.dao.mapper;

import com.astral.dao.entity.DictData;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据字典数据表 Mapper 接口
 * 继承 MyBatis-Plus BaseMapper，提供基础 CRUD 操作
 */
@Mapper
public interface DictDataMapper extends BaseMapper<DictData> {

    /**
     * 按字典类型编码(dict_code)查询启用的字典数据，按 dict_sort 升序
     *
     * @param code 字典类型编码
     * @return 字典数据列表
     */
    @Select("SELECT d.* FROM sys_dict_data d INNER JOIN sys_dict_type t ON d.dict_type_id = t.id " +
            "WHERE t.dict_code = #{code} AND d.status = 1 ORDER BY d.dict_sort ASC, d.id ASC")
    List<DictData> selectByDictCode(@Param("code") String code);
}
