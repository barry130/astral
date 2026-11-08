package com.astral.server.config;

import com.astral.common.util.PageQuery;
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

    /**
     * 分页拦截器。
     *
     * <p><b>为什么要设 maxLimit</b>：管理端接口的 {@code pageSize} 全部直接取自 query
     * （如 {@code ?pageSize=1000000}），原样进 SQL 的 {@code LIMIT}，单请求即可拉全表。
     * {@code astral-common} 早就有 {@link PageQuery#MAX_PAGE_SIZE} 这个统一上限，
     * 但只有 {@code TokenServiceImpl} 在用；逐个控制器改既漏又易回归，
     * 因此在分页拦截器上做<b>一处全局兜底</b>：超过上限的 pageSize 按上限取（不报错）。</p>
     *
     * <p>上限直接引用 {@link PageQuery#MAX_PAGE_SIZE}，不再另立魔法数——
     * 该值同时被手工分页（TokenServiceImpl）与前端 pageSizeOptions 共享，改一处即全链路生效。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor();
        paginationInterceptor.setMaxLimit((long) PageQuery.MAX_PAGE_SIZE);
        interceptor.addInnerInterceptor(paginationInterceptor);
        return interceptor;
    }
}
