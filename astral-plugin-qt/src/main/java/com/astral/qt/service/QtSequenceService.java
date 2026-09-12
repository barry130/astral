package com.astral.qt.service;

import com.astral.sequence.service.GeneratorFactory;

import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 轻听插件实体 ID 分配
 * <p>qt 插件实体走全局序列（业务键 = 表名_id），此处统一取号，避免依赖 MetaObjectHandler 的自动填充。</p>
 */
@Service
public class QtSequenceService {

    @Resource
    private GeneratorFactory generatorFactory;

    /** 为指定表分配下一个实体 ID（业务键 = 表名_id，强制号段模式） */
    public long nextId(String tableName) {
        return generatorFactory.next(tableName + "_id", "SEGMENT");
    }
}
