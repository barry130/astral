package com.astral.feedback.service;

import com.astral.sequence.service.GeneratorFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * 反馈插件实体 ID 分配
 * <p>反馈插件实体走全局序列（业务键 = 表名_id），此处统一取号。</p>
 */
@Service
public class FeedbackSequenceService {

    @Resource
    private GeneratorFactory generatorFactory;

    /** 为指定表分配下一个实体 ID（业务键 = 表名_id，强制号段模式） */
    public long nextId(String tableName) {
        return generatorFactory.next(tableName + "_id", "SEGMENT");
    }
}
