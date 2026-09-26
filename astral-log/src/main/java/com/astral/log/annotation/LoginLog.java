package com.astral.log.annotation;

import java.lang.annotation.*;

/**
 * 登录日志注解
 * <p>
 * 用于标记需要记录登录日志的方法（通常是登录接口）。配合 {@link com.astral.log.aspect.LoginLogAspect} 使用，
 * 通过AOP拦截登录方法，自动记录登录用户、登录方式、IP地址、登录结果等信息。
 * </p>
 *
 * @see com.astral.log.aspect.LoginLogAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LoginLog {
    /**
     * 登录方式描述
     * <p>
     * 例如："用户名密码登录"、"短信验证码登录"、"第三方登录"等。
     * 默认值为"登录"。
     * </p>
     *
     * @return 登录方式描述
     */
    String value() default "登录";
}