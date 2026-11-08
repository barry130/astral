package com.astral.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link RequiresPermission} 的可重复注解容器。
 *
 * <p>同一方法或类上的每组权限均须满足；各组内部保留自身的 ANY/ALL 模式和显式 API/DATA 类型。
 * 方法声明的完整组覆盖类声明的完整组，不按权限码段数推断类型。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface RequiresPermissions {
    RequiresPermission[] value();
}
