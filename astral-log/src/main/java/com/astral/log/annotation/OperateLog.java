package com.astral.log.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * <p>
 * 用于标记需要记录操作日志的方法。配合 {@link com.astral.log.aspect.OperateLogAspect} 使用，
 * 通过AOP拦截被标注的方法，自动记录操作人、操作类型、请求参数、响应结果等信息。
 * </p>
 *
 * @see com.astral.log.aspect.OperateLogAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperateLog {
    /**
     * 操作类型描述，用于标识具体的操作行为
     * <p>
     * 例如："新增用户"、"删除角色"、"修改配置"等。
     * 如果未指定，则默认使用方法名作为操作类型。
     * </p>
     *
     * @return 操作类型描述
     */
    String value() default "";
}
