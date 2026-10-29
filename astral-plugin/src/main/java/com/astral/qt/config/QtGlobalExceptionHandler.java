package com.astral.qt.config;

import com.astral.common.exception.BusinessException;
import com.astral.qt.common.QtException;
import com.astral.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 轻听插件统一异常处理
 * <p>仅作用于 com.astral.qt 包下控制器，返回与前端约定的 {code,msg,data}。</p>
 * <p>V2 契约：HTTP 恒 200（表示服务端已处理），成败只由 body 的 code 表达
 * （200 成功 / 300 业务失败 / 320 参数错误 / 401 未登录 / 403 无权限 / 500 系统错误），
 * body.errorCode 保留细分码。</p>
 */
@Slf4j
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(basePackages = "com.astral.qt.controller")
public class QtGlobalExceptionHandler {

    @ExceptionHandler(QtException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleQtException(QtException e) {
        log.warn("[QtPlugin] 业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /** 插件被禁用时拦截器抛出的 BusinessException：透传错误码中的语义，但以 qt 包装返回 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("[QtPlugin] 拦截器业务异常 code={}, msg={}", e.getErrorCode(), e.getMessage());
        return Result.error(e.getCode() != null ? e.getCode() : 300, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.error(320, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.error(320, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error(320, "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("[QtPlugin] 系统异常", e);
        return Result.error(500, "系统出现错误");
    }
}
