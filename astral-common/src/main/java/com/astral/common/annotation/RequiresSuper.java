package com.astral.common.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 声明式超级管理员校验：标注的方法（或整个控制器）要求当前登录用户是超级管理员。
 *
 * <p>用于<b>提权类</b>接口：重置他人密码、修改用户角色、变更角色权限、增删权限定义。
 * 这类操作一旦被普通管理员执行，即可给自己加上任意权限，因此要求 {@code *:*:*}（超管通配）。</p>
 *
 * <p>超管身份的来源是 {@code sys_role.is_super = 1} 的启用角色（不再依赖在 sys_role_permission
 * 里绑定一条 {@code *:*:*} 记录），由 StpInterfaceImpl 在读权限列表时合成 {@code *:*:*}。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface RequiresSuper {}
