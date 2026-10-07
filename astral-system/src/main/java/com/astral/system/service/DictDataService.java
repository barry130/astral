package com.astral.system.service;

import com.astral.dao.entity.DictData;
import com.baomidou.mybatisplus.spring.service.IService;

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

    /**
     * 按字典类型编码(dict_code)查询<b>已停用</b>的字典数据（按 dict_sort 升序）
     * <p>用于服务端识别「已废弃的取值」——它们不出现在前端下拉里，但历史数据里可能还残留，
     * 需要在继承/迁移时摘掉。典型场景：音源包产物 path 字典停用了 chain.json / source-bundle.js。</p>
     *
     * @param code 字典类型编码
     * @return 已停用（status=0）的字典数据列表
     */
    List<DictData> listDisabledByCode(String code);
}
