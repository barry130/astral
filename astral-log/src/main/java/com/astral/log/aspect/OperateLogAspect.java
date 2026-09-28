package com.astral.log.aspect;

import com.astral.log.service.LogService;
import cn.hutool.core.util.StrUtil;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

/**
 * 操作日志AOP切面
 * <p>
 * 拦截所有标注了 {@link com.astral.log.annotation.OperateLog} 注解的方法，
 * 自动捕获操作日志信息并通过异步方式保存到数据库。
 * 记录内容包括：操作人、操作类型、请求URL、请求方法、请求参数、响应结果、IP地址、执行状态、执行耗时等。
 * </p>
 * <p>
 * 使用异步线程池 {@code sequenceAsyncExecutor} 进行日志保存，避免影响主业务流程性能。
 * </p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperateLogAspect {

    /** 日志服务，用于持久化操作日志 */
    private final LogService logService;
    /** JSON序列化器，用于将请求参数和响应结果转换为JSON字符串 */
    private final ObjectMapper objectMapper;

    /**
     * 定义切点：拦截所有标注了 @OperateLog 注解的方法
     */
    @Pointcut("@annotation(com.astral.log.annotation.OperateLog)")
    public void operateLogPointcut() {
    }

    /**
     * 环绕通知：在目标方法执行前后捕获日志信息
     * <p>
     * 执行流程：
     * 1. 记录开始时间，获取请求上下文信息（用户、IP、URL等）
     * 2. 执行目标方法，捕获请求参数和响应结果
     * 3. 计算执行耗时，异步保存日志
     * 4. 如果方法抛出异常，记录异常状态和错误信息
     * </p>
     *
     * @param joinPoint 连接点，包含目标方法的上下文信息
     * @return 目标方法的返回值
     * @throws Throwable 目标方法抛出的异常会原样向上抛出
     */
    @Around("operateLogPointcut()")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        HttpServletRequest request = getRequest();
        String username = getUsername(request);
        String ip = getIp(request);
        String methodName = joinPoint.getSignature().getName();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String operateType = getOperateType(joinPoint);
        String requestUrl = request != null ? request.getRequestURI() : "";
        String requestMethod = request != null ? request.getMethod() : "";

        Object result = null;
        Integer status = 1;
        String errorMsg = null;
        String requestParams = "";
        String responseResult = "";

        try {
            requestParams = getRequestParams(joinPoint);
            result = joinPoint.proceed();
            responseResult = getResponseResult(result);
            long executeTime = System.currentTimeMillis() - startTime;
            saveLogAsync(username, operateType, requestUrl, requestMethod, requestParams, responseResult, ip, status, errorMsg, executeTime);
            return result;
        } catch (Exception e) {
            status = 0;
            errorMsg = e.getMessage();
            long executeTime = System.currentTimeMillis() - startTime;
            saveLogAsync(username, operateType, requestUrl, requestMethod, requestParams, responseResult, ip, status, errorMsg, executeTime);
            throw e;
        }
    }

    /**
     * 异步保存操作日志
     * <p>
     * 使用 {@code @Async("sequenceAsyncExecutor")} 注解，将日志保存操作提交到独立线程池执行，
     * 确保不会影响主业务流程的响应速度。
     * </p>
     *
     * @param username       操作用户名
     * @param operateType    操作类型
     * @param requestUrl     请求URL
     * @param requestMethod  请求方法（GET/POST/PUT/DELETE等）
     * @param requestParams  请求参数（JSON格式）
     * @param responseResult 响应结果（JSON格式）
     * @param ip             客户端IP地址
     * @param status         执行状态（1=成功，0=失败）
     * @param errorMsg       错误信息（失败时记录）
     * @param executeTime    执行耗时（毫秒）
     */
    @Async("sequenceAsyncExecutor")
    public void saveLogAsync(String username, String operateType, String requestUrl, String requestMethod,
                             String requestParams, String responseResult, String ip, Integer status, String errorMsg, long executeTime) {
        saveLog(username, operateType, requestUrl, requestMethod, requestParams, responseResult, ip, status, errorMsg, executeTime);
    }

    /**
     * 实际保存日志的方法
     * <p>
     * 构建 {@link com.astral.dao.entity.OperateLog} 实体对象并调用服务层保存。
     * 内部捕获异常，确保日志保存失败不会影响主流程。
     * </p>
     */
    private void saveLog(String username, String operateType, String requestUrl, String requestMethod,
                    String requestParams, String responseResult, String ip, Integer status, String errorMsg, long executeTime) {
        try {
            log.info("保存操作日志: operateType={}, status={}", operateType, status);
            com.astral.dao.entity.OperateLog operateLog = new com.astral.dao.entity.OperateLog();
            operateLog.setUsername(username);
            operateLog.setModule("API");
            operateLog.setOperateType(operateType);
            operateLog.setRequestUrl(requestUrl);
            operateLog.setRequestMethod(requestMethod);
            operateLog.setRequestParams(requestParams);
            operateLog.setResponseResult(responseResult);
            operateLog.setIp(ip);
            operateLog.setLocation("");
            operateLog.setStatus(status);
            operateLog.setErrorMsg(errorMsg);
            operateLog.setExecuteTime(executeTime);
            operateLog.setCreateTime(LocalDateTime.now());
            logService.saveOperateLog(operateLog);
            log.info("操作日志保存成功");
        } catch (Exception e) {
            log.error("保存操作日志失败", e);
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
     * 从请求属性中获取当前用户名
     * <p>
     * 用户名由认证拦截器在请求属性中设置。如果请求为空或未找到用户名属性，返回 "anonymous"。
     * </p>
     *
     * @param request HTTP请求对象
     * @return 当前用户名或 "anonymous"
     */
    private String getUsername(HttpServletRequest request) {
        if (request == null) return "anonymous";
        Object user = request.getAttribute("username");
        return user != null ? user.toString() : "anonymous";
    }

    /**
     * 获取客户端真实IP地址
     * <p>
     * 按优先级尝试获取IP：
     * 1. X-Forwarded-For 头（反向代理场景）
     * 2. X-Real-IP 头（Nginx等代理服务器）
     * 3. request.getRemoteAddr()（直连场景）
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
     * 从 @OperateLog 注解中获取操作类型描述
     * <p>
     * 优先使用注解的 value 值，如果未指定则使用方法名作为操作类型。
     * </p>
     *
     * @param joinPoint 连接点
     * @return 操作类型描述
     */
    private String getOperateType(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        com.astral.log.annotation.OperateLog annotation = method.getAnnotation(com.astral.log.annotation.OperateLog.class);
        if (annotation != null && StrUtil.isNotEmpty(annotation.value())) {
            return annotation.value();
        }
        return signature.getName();
    }

    /**
     * 序列化请求参数为JSON字符串
     * <p>
     * 取方法第一个参数进行序列化（跳过 HttpServletRequest 类型参数）。
     * 如果序列化失败，返回空字符串。
     * </p>
     *
     * @param joinPoint 连接点
     * @return 请求参数的JSON字符串
     */
    private String getRequestParams(ProceedingJoinPoint joinPoint) {
        try {
            Object[] args = joinPoint.getArgs();
            if (args == null || args.length == 0) return "";
            Object params = args[0];
            if (params instanceof HttpServletRequest) return "";
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 序列化响应结果为JSON字符串
     *
     * @param result 方法返回值
     * @return 响应结果的JSON字符串，如果为null或序列化失败则返回空字符串
     */
    private String getResponseResult(Object result) {
        try {
            if (result == null) return "";
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            return "";
        }
    }
}