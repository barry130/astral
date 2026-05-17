package com.astral.common.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    String key() default "ip";
    int limit() default 60;
    int duration() default 60;
    String message() default "请求过于频繁，请稍后再试";
}
