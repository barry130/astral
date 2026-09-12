package com.astral.system.service;

import com.astral.dao.entity.DictData;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 字典数据服务接口
 * <p>继承MyBatis-Plus的IService，提供字典数据实体的标准CRUD操作</p>
 */
public interface DictDataService extends IService<DictData> {

    /**
     * 按字典类型编码(dict_code)查询启用的字典数据（按 dict_sort 升序）
     *
     * @param code 字典类型编码
     * @return 字典数据列表
     */
    List<DictData> listByCode(String code);
}
