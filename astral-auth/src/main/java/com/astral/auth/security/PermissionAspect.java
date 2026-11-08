package com.astral.auth.security;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import java.lang.reflect.Method;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

/**
 * {@code @RequiresPermission} / {@code @RequiresSuper} 的切面实现。
 *
 * <p>方法上的注解优先；方法上没有时回退到<b>类级注解</b>（整个控制器统一要求某权限）。
 * 未登录一律放行（认证由 AuthInterceptor 负责，与历史 {@code require()} 语义保持一致：
 * 免认证/App 公共接口上的注解不会因为「没登录」而误拒）。</p>
 *
 * <p>校验失败抛 {@code BusinessException(COMMON005)}，由 GlobalExceptionHandler 统一转成
 * 业务错误响应，与手写校验的对外行为完全一致。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final PermissionChecker permissionChecker;

    /**
     * 所有声明组分别校验；方法完整声明优先于类声明，只执行一次权限 advice。
     * 控制器接口上的注解不属于实现方法的 execution annotation，必须先匹配控制器，再解析其接口声明。
     * 未声明权限的控制器仍直接放行，不把全部业务 bean 纳入代理范围。
     */
    @Around("@annotation(com.astral.common.annotation.RequiresPermission) "
            + "|| @annotation(com.astral.common.annotation.RequiresPermissions) "
            + "|| @within(com.astral.common.annotation.RequiresPermission) "
            + "|| @within(com.astral.common.annotation.RequiresPermissions) "
            + "|| (within(com.astral..*) "
            + "&& (@within(org.springframework.web.bind.annotation.RestController) "
            + "|| @within(org.springframework.stereotype.Controller)))")
    public Object aroundPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        for (RequiresPermission annotation : findPermissions(joinPoint)) {
            checkPermission(joinPoint, annotation);
        }
        return joinPoint.proceed();
    }

    /** 超管校验（方法级或类级） */
    @Around("@annotation(com.astral.common.annotation.RequiresSuper) "
            + "|| @within(com.astral.common.annotation.RequiresSuper)")
    public Object aroundRequiresSuper(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!StpUtil.isLogin() || permissionChecker.isSuperUser()) {
            return joinPoint.proceed();
        }
        log.warn(
                "拒绝非超管调用: {}#{}",
                joinPoint.getSignature().getDeclaringType().getSimpleName(),
                joinPoint.getSignature().getName());
        throw new BusinessException("COMMON005");
    }

    // ==================== 内部实现 ====================

    private void checkPermission(ProceedingJoinPoint joinPoint, RequiresPermission annotation) {
        if (annotation == null || annotation.value().length == 0 || !StpUtil.isLogin()) {
            return;
        }
        String[] codes = annotation.value();
        boolean pass = annotation.mode() == RequiresPermission.Mode.ALL
                ? hasAll(codes)
                : permissionChecker.hasAnyPermission(codes);
        if (pass) {
            return;
        }
        log.warn(
                "权限校验失败: userId={}, 需要={}({})",
                StpUtil.getLoginIdDefaultNull(),
                String.join(",", codes),
                annotation.mode());
        throw new BusinessException("COMMON005");
    }

    private boolean hasAll(String[] codes) {
        for (String code : codes) {
            if (!permissionChecker.hasPermission(code)) {
                return false;
            }
        }
        return true;
    }

    /** 最具体实现方法优先；没有本地声明才查接口/继承方法，最后回退到类。 */
    private Set<RequiresPermission> findPermissions(ProceedingJoinPoint joinPoint) {
        Class<?> targetClass = AopUtils.getTargetClass(joinPoint.getTarget());
        Method method =
                AopUtils.getMostSpecificMethod(((MethodSignature) joinPoint.getSignature()).getMethod(), targetClass);
        Set<RequiresPermission> annotations =
                AnnotatedElementUtils.getMergedRepeatableAnnotations(method, RequiresPermission.class);
        if (annotations.isEmpty()) {
            annotations = AnnotatedElementUtils.findMergedRepeatableAnnotations(method, RequiresPermission.class);
        }
        if (!annotations.isEmpty()) {
            return annotations;
        }
        return AnnotatedElementUtils.findMergedRepeatableAnnotations(targetClass, RequiresPermission.class);
    }
}
