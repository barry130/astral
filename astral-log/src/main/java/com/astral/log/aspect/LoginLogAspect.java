package com.astral.log.aspect;

import com.astral.log.service.LogService;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 登录日志AOP切面
 * <p>
 * 拦截所有标注了 {@link com.astral.log.annotation.LoginLog} 注解的方法（通常是登录接口），
 * 自动捕获登录日志信息并同步保存到数据库。
 * 记录内容包括：用户名、登录方式、IP地址、登录状态、登录结果消息等。
 * </p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class LoginLogAspect {

    /** 日志服务，用于持久化登录日志 */
    private final LogService logService;

    /**
     * 定义切点：拦截所有标注了 @LoginLog 注解的方法
     */
    @Pointcut("@annotation(com.astral.log.annotation.LoginLog)")
    public void loginLogPointcut() {
    }

    /**
     * 环绕通知：在登录方法执行前后捕获日志信息
     * <p>
     * 执行流程：
     * 1. 从方法参数中提取用户名（通过反射调用 get getUsername 方法）
     * 2. 获取客户端IP地址和登录方式
     * 3. 执行登录方法，根据成功/失败状态记录日志
     * </p>
     *
     * @param joinPoint 连接点，包含登录方法的上下文信息
     * @return 登录方法的返回值
     * @throws Throwable 登录方法抛出的异常会原样向上抛出
     */
    @Around("loginLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        String username = getUsername(joinPoint);
        HttpServletRequest request = getRequest();
        String ip = getIp(request);
        String loginType = getLoginType(joinPoint);

        Integer status = 1;
        String msg = "登录成功";

        Object result = null;
        try {
            result = joinPoint.proceed();
            saveLoginLog(username, loginType, ip, status, msg);
            return result;
        } catch (Exception e) {
            status = 0;
            msg = e.getMessage();
            saveLoginLog(username, loginType, ip, status, msg);
            throw e;
        }
    }

    /**
     * 保存登录日志到数据库
     * <p>
     * 构建 {@link com.astral.dao.entity.LoginLog} 实体对象并调用服务层保存。
     * 内部捕获异常，确保日志保存失败不会影响登录主流程。
     * </p>
     *
     * @param username  用户名
     * @param loginType 登录方式
     * @param ip        客户端IP地址
     * @param status    登录状态（1=成功，0=失败）
     * @param msg       登录结果消息
     */
    private void saveLoginLog(String username, String loginType, String ip, Integer status, String msg) {
        try {
            log.info("保存登录日志: username={}, loginType={}, status={}, ip={}", username, loginType, status, ip);
            com.astral.dao.entity.LoginLog loginLog = new com.astral.dao.entity.LoginLog();
            loginLog.setUsername(username);
            loginLog.setLoginType(loginType);
            loginLog.setIp(ip);
            loginLog.setLocation("");
            loginLog.setStatus(status);
            loginLog.setMsg(msg);
            loginLog.setLoginTime(LocalDateTime.now());
            log.info("调用logService.saveLoginLog");
            logService.saveLoginLog(loginLog);
            log.info("登录日志保存成功");
        } catch (Exception e) {
            log.error("保存登录日志失败", e);
        }
    }

    /**
     * 从 Spring 请求上下文中获取 HttpServletRequest 对象
     *
     * @return HttpServletRequest，如果上下文不可用则返回 null
     */
    private HttpServletRequest getRequest() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        return attributes != null ? attributes.getRequest() : null;
    }

    /**
     * 从方法参数中提取用户名
     * <p>
     * 通过反射调用第一个参数的 {@code getUsername()} 方法获取用户名。
     * 这种方式使切面可以适配不同的登录请求DTO类型，只要它们提供了 getUsername 方法。
     * </p>
     *
     * @param joinPoint 连接点
     * @return 用户名，如果无法获取则返回空字符串
     */
    private String getUsername(ProceedingJoinPoint joinPoint) {
        Object[] args = joinPoint.getArgs();
        if (args.length > 0 && args[0] != null) {
            Object param = args[0];
            try {
                Method getUsername = param.getClass().getMethod("getUsername");
                Object username = getUsername.invoke(param);
                return username != null ? username.toString() : "";
            } catch (Exception e) {
            }
        }
        return "";
    }

    /**
     * 获取客户端真实IP地址
     * <p>
     * 按优先级尝试获取IP：X-Forwarded-For -> X-Real-IP -> RemoteAddr
     * </p>
     *
     * @param request HTTP请求对象
     * @return 客户端IP地址
     */
    private String getIp(HttpServletRequest request) {
        if (request == null) return "unknown";
        String ip = request.getHeader("X-Forwarded-For");
        if (StrUtil.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (StrUtil.isEmpty(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }

    /**
     * 从 @LoginLog 注解中获取登录方式描述
     *
     * @param joinPoint 连接点
     * @return 登录方式描述，默认返回"登录"
     */
    private String getLoginType(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        com.astral.log.annotation.LoginLog annotation = method.getAnnotation(com.astral.log.annotation.LoginLog.class);
        if (annotation != null && StrUtil.isNotEmpty(annotation.value())) {
            return annotation.value();
        }
        return "登录";
    }
}