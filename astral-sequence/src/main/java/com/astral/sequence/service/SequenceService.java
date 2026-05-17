package com.astral.sequence.service;

import com.astral.sequence.generator.SequenceGenerator;
import com.astral.sequence.generator.SnowflakeGenerator;
import com.astral.sequence.generator.SimpleGenerator;
import com.astral.sequence.generator.DatabaseGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 序列号服务
 * <p>
 * 作为序列号生成的门面服务，对外提供统一的序列号获取接口。
 * 内部通过 {@link GeneratorFactory} 根据配置动态选择具体的生成器实现。
 * </p>
 * <p>
 * 该服务层的主要职责：
 * <ul>
 *   <li>处理类型参数的空值情况</li>
 *   <li>委托给 GeneratorFactory 进行实际的序列号生成</li>
 *   <li>为控制器层提供简洁的 API</li>
 * </ul>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SequenceService {
    /** 生成器工厂，负责根据配置选择和管理具体的生成器实例 */
    private final GeneratorFactory generatorFactory;

    /**
     * 获取下一个序列号
     *
     * @param bizKey 业务键，用于区分不同业务的序列号空间
     * @param type   可选的生成器类型，如果为 null 或空字符串则使用默认类型
     * @return 生成的序列号
     */
    public long next(String bizKey, String type) {
        // 将空字符串统一处理为 null，由 GeneratorFactory 决定使用默认类型
        String effectiveType = type != null && !type.isEmpty() ? type : null;
        return generatorFactory.next(bizKey, effectiveType);
    }

    /**
     * 批量获取序列号
     *
     * @param bizKey 业务键
     * @param count  生成数量
     * @param type   可选的生成器类型
     * @return 逗号分隔的序列号字符串
     */
    public String batch(String bizKey, int count, String type) {
        String effectiveType = type != null && !type.isEmpty() ? type : null;
        return generatorFactory.batch(bizKey, count, effectiveType);
    }
}