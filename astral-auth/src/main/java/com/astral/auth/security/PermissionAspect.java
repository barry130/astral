package com.astral.auth.security;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
import com.astral.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

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

    /** 方法级权限校验 */
    @Around("@annotation(com.astral.common.annotation.RequiresPermission)")
    public Object aroundMethodPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        checkPermission(joinPoint, findAnnotation(joinPoint, RequiresPermission.class));
        return joinPoint.proceed();
    }

    /** 类级权限校验（方法自身带注解时跳过，避免与方法级切面重复校验） */
    @Around("@within(com.astral.common.annotation.RequiresPermission)")
    public Object aroundTypePermission(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        if (AnnotationUtils.findAnnotation(method, RequiresPermission.class) == null) {
            checkPermission(joinPoint, findAnnotation(joinPoint, RequiresPermission.class));
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
        log.warn("拒绝非超管调用: {}#{}", joinPoint.getSignature().getDeclaringType().getSimpleName(),
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
        log.warn("权限校验失败: userId={}, 需要={}({})",
                StpUtil.getLoginIdDefaultNull(), String.join(",", codes), annotation.mode());
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

    /** 先取方法注解，再回退类注解 */
    private <A extends Annotation> A findAnnotation(ProceedingJoinPoint joinPoint, Class<A> type) {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        A annotation = AnnotationUtils.findAnnotation(method, type);
        if (annotation != null) {
            return annotation;
        }
        return AnnotationUtils.findAnnotation(joinPoint.getTarget().getClass(), type);
    }
}
