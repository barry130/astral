package com.astral.qt.config;

import com.astral.common.exception.BusinessException;
import com.astral.qt.common.QtException;
import com.astral.qt.common.QtRestResp;
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
 */
@Slf4j
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(basePackages = "com.astral.qt.controller")
public class QtGlobalExceptionHandler {

    @ExceptionHandler(QtException.class)
    @ResponseStatus(HttpStatus.OK)
    public QtRestResp<Void> handleQtException(QtException e) {
        log.warn("[QtPlugin] 业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return QtRestResp.error(e.getCode(), e.getMessage());
    }

    /** 插件被禁用时拦截器抛出的 BusinessException：透传错误码中的语义，但以 qt 包装返回 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public QtRestResp<Void> handleBusinessException(BusinessException e) {
        log.warn("[QtPlugin] 拦截器业务异常 code={}, msg={}", e.getErrorCode(), e.getMessage());
        return QtRestResp.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public QtRestResp<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return QtRestResp.error(320, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public QtRestResp<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return QtRestResp.error(320, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public QtRestResp<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return QtRestResp.error(320, "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public QtRestResp<Void> handleException(Exception e) {
        log.error("[QtPlugin] 系统异常", e);
        return QtRestResp.error(300, "系统出现错误");
    }
}