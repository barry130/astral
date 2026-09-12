package com.astral.server.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主 MyBatis-Plus 配置
 * <p>
 * 仅注册分页拦截器等通用组件。实体 ID 自动填充（MetaObjectHandler）
 * 由序列模块自身的 {@code SequenceAutoConfiguration} 注册，宿主不再直接依赖序列模块内部类。
 * </p>
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor();
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }
}
