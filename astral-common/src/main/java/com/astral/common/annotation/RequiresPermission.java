package com.astral.common.annotation;

import com.astral.common.constant.PermissionType;

import java.lang.annotation.*;

/**
 * 声明式权限校验：标注的方法（或整个控制器）要求当前登录用户具备指定权限。
 *
 * <p>由 astral-auth 的 {@code PermissionAspect} 在方法执行前校验，权限不足抛
 * {@code BusinessException(COMMON005)}，取代控制器里手写的 {@code permissionChecker.require(...)}。
 * 未登录时不在此处拦截（认证由 AuthInterceptor 负责），与历史 {@code require()} 语义一致。</p>
 *
 * <p><b>权限码规范</b>：{@code 端:域:资源:操作[:范围]}，全小写。
 * <b>端</b>（首段）∈ {@code admin}（管理端 {@code /api/v1/admin/**}）/ {@code user}（App 端 {@code /api/v1/app/**}）/
 * {@code all}（两端共用 {@code /api/v1/all/**}）；<b>域</b>（第 2 段）对应 {@code sys_permission.domain}。
 * 例如：</p>
 * <pre>
 * &#64;RequiresPermission("admin:system:user:view")                        // 单权限
 * &#64;RequiresPermission(value = {"admin:system:user:edit", "admin:system:user:add"}, mode = RequiresPermission.Mode.ANY)
 * &#64;RequiresPermission(value = "admin:system:user:edit", name = "用户编辑")   // 顺带登记到权限表（启动自动注册）
 * </pre>
 *
 * <p>用户侧持有的权限支持层级通配：持有 {@code admin:system:user:*} 即通过 {@code admin:system:user:view} 校验
 * （通配必须带上「端」段，不要写成 {@code system:user:*}）；
 * 超级管理员（持 {@code *:*:*}，由 sys_role.is_super 角色合成）通过一切校验。</p>
 *
 * @see RequiresSuper
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {

    /** 权限码列表（按 {@link #mode()} 组合判定） */
    String[] value();

    /** 多权限码的组合方式 */
    Mode mode() default Mode.ANY;

    /**
     * 权限名称（管理端展示）。留空时前端展示权限码本身。
     * <p>仅在权限自动注册时使用：库里已存在同编码权限时不会覆盖后台已改过的名称。</p>
     */
    String name() default "";

    /** 权限说明（管理端 tooltip），可为空 */
    String description() default "";

    /** 权限域。留空时取「端」段之后的第 1 段（如 {@code admin:system:user:view} → {@code system}） */
    String domain() default "";

    /** 权限类型，默认接口权限（{@link PermissionType#API}）；结果级权限用 {@link PermissionType#DATA} */
    int type() default PermissionType.API;

    /** 多权限码组合方式 */
    enum Mode {
        /** 具备任一权限即通过 */
        ANY,
        /** 必须同时具备全部权限 */
        ALL
    }
}
