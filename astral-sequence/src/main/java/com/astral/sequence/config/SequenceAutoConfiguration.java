package com.astral.sequence.config;

import com.astral.sequence.generator.SegmentGenerator;
import com.astral.sequence.service.GeneratorFactory;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 序列模块自动装配
 * <p>
 * 将序列模块自身的 Bean（如 MyBatis-Plus {@link MetaObjectHandler}）在此注册，
 * 使序列模块在代码层自包含，宿主无需直接 import 序列模块的内部类。
 * </p>
 * <p>
 * 序列服务是系统必需插件（{@code isRequired=true}），承载全局实体 ID 生成，
 * Maven 层仍为宿主硬依赖，但 Bean 装配逻辑收敛在模块内部。
 * </p>
 */
@Configuration
public class SequenceAutoConfiguration {

    /**
     * MyBatis-Plus 元对象处理器
     * <p>
     * 自动填充业务实体的 id / createTime / updateTime。
     * 使用 @Lazy 延迟注入 {@link SegmentGenerator}，避免与序列初始化的循环依赖。
     * </p>
     */
    @Bean
    public MetaObjectHandler metaObjectHandler(
            @Lazy SegmentGenerator segmentGenerator,
            EntityIdSequenceProvider entityIdSequenceProvider,
            @Lazy GeneratorFactory generatorFactory) {
        return new SequenceMetaObjectHandler(segmentGenerator, entityIdSequenceProvider, generatorFactory);
    }
}
