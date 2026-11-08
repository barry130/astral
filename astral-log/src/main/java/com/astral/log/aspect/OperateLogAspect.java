package com.astral.log.aspect;

import cn.hutool.core.util.StrUtil;
import com.astral.common.util.ClientIp;
import com.astral.common.util.LogSanitizer;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * 操作日志AOP切面
 * <p>
 * 拦截所有标注了 {@link com.astral.log.annotation.OperateLog} 注解的方法，
 * 自动捕获操作日志信息并通过异步方式保存到数据库。
 * 记录内容包括：操作人、操作类型、请求URL、请求方法、请求参数、响应结果、IP地址、执行状态、执行耗时等。
 * </p>
 * <p>
 * 使用异步线程池 {@code sequenceAsyncExecutor} 进行日志保存，避免影响主业务流程性能。
 * 异步写入由 {@link OperateLogWriter} 承担：{@code @Async} 依赖代理，
 * 写在切面类里再由切面自己调用是不生效的（详见该类的注释）。
 * </p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperateLogAspect {

    /** 异步日志写入器：@Async 必须落在另一个 Bean 上才会走代理 */
    private final OperateLogWriter operateLogWriter;
    /** JSON序列化器，用于将请求参数和响应结果转换为JSON字符串 */
    private final ObjectMapper objectMapper;

    /** 落库的请求/响应文本上限（字符） */
    private static final int MAX_PERSISTED_LENGTH = 8 * 1024;

    /** 可信代理列表（决定能否采信 X-Forwarded-For） */
    @Value("${astral.web.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;

    /**
     * 定义切点：拦截所有标注了 @OperateLog 注解的方法
     */
    @Pointcut("@annotation(com.astral.log.annotation.OperateLog)")
    public void operateLogPointcut() {}

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
        Long userId = getUserId(request);
        String username = getUsername(request);
        String ip = getIp(request);
        com.astral.log.annotation.OperateLog operation = getOperateLog(joinPoint);
        boolean recordPayloads = operation == null || operation.recordPayloads();
        String operateType = operation != null && StrUtil.isNotEmpty(operation.value())
                ? operation.value()
                : joinPoint.getSignature().getName();
        String requestUrl = request != null ? request.getRequestURI() : "";
        String requestMethod = request != null ? request.getMethod() : "";

        Object result = null;
        Integer status = 1;
        String errorMsg = null;
        String requestParams = "";
        String responseResult = "";

        try {
            requestParams = recordPayloads ? getRequestParams(joinPoint) : "";
            result = joinPoint.proceed();
            responseResult = recordPayloads ? getResponseResult(result) : "";
            long executeTime = System.currentTimeMillis() - startTime;
            safeSave(
                    userId,
                    username,
                    operateType,
                    requestUrl,
                    requestMethod,
                    requestParams,
                    responseResult,
                    ip,
                    status,
                    errorMsg,
                    executeTime);
            return result;
        } catch (Exception e) {
            status = 0;
            errorMsg = recordPayloads
                    ? truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                    : e.getClass().getSimpleName();
            long executeTime = System.currentTimeMillis() - startTime;
            safeSave(
                    userId,
                    username,
                    operateType,
                    requestUrl,
                    requestMethod,
                    requestParams,
                    responseResult,
                    ip,
                    status,
                    errorMsg,
                    executeTime);
            throw e;
        }
    }

    /**
     * 日志提交失败绝不能覆盖/放大业务异常：异步线程池拒绝任务时，代理会在调用点直接抛出
     * {@code TaskRejectedException}。此处兜底记录后吞掉，保证日志始终是旁路。
     */
    private void safeSave(
            Long userId,
            String username,
            String operateType,
            String requestUrl,
            String requestMethod,
            String requestParams,
            String responseResult,
            String ip,
            Integer status,
            String errorMsg,
            long executeTime) {
        try {
            operateLogWriter.saveAsync(
                    userId,
                    username,
                    operateType,
                    requestUrl,
                    requestMethod,
                    requestParams,
                    responseResult,
                    ip,
                    status,
                    errorMsg,
                    executeTime);
        } catch (Exception e) {
            log.error("提交操作日志异步任务失败: operateType={}", operateType, e);
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
     * 从请求属性中获取当前用户ID。
     *
     * <p>管理端拦截器写入的是字符串，App 插件拦截器写入的是 Long；统一在此归一。
     * 未登录、匿名请求或属性非法时返回 {@code null}，由日志表保留空用户ID。</p>
     *
     * @param request HTTP请求对象
     * @return 当前用户ID，无法解析时为 null
     */
    private Long getUserId(HttpServletRequest request) {
        if (request == null) return null;
        Object user = request.getAttribute("userId");
        if (user == null) return null;
        if (user instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(user.toString());
        } catch (NumberFormatException e) {
            return null;
        }
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
        // 走可信代理白名单解析：无条件采信 X-Forwarded-For 会让审计日志里的来源 IP 可伪造
        return ClientIp.resolve(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies);
    }

    /** Resolve the target declaration once for both its audit label and payload policy. */
    private com.astral.log.annotation.OperateLog getOperateLog(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = AopUtils.getMostSpecificMethod(
                signature.getMethod(), joinPoint.getTarget().getClass());
        return AnnotationUtils.findAnnotation(method, com.astral.log.annotation.OperateLog.class);
    }

    /**
     * 截断超长文本：日志列是 TEXT，但把整页列表 / 大响应原样持久化既无检索价值
     * 又会把库撑大（且拖慢异步写入）。保留头部足以定位问题。
     */
    private String truncate(String text) {
        if (text == null || text.length() <= MAX_PERSISTED_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_PERSISTED_LENGTH) + "...(truncated)";
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
            // 凭据脱敏必须在落库前完成：改密码 / 重置密码 / 邮箱授权码 / 第三方密钥
            // 都是从请求体经过的，「能看日志」不等于「该看到明文凭据」。
            return truncate(LogSanitizer.mask(objectMapper.writeValueAsString(params)));
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
            return truncate(LogSanitizer.mask(objectMapper.writeValueAsString(result)));
        } catch (Exception e) {
            return "";
        }
    }
}
