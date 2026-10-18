package com.astral.ai.config;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiRestResp;
import com.astral.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * AI 插件统一异常处理。
 *
 * <p>与 {@code FeedbackGlobalExceptionHandler} 同构：只作用于 AI 控制器，
 * 避免影响宿主其他模块的异常语义。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(basePackages = "com.astral.ai.controller")
public class AiGlobalExceptionHandler {

    @ExceptionHandler(AiException.class)
    @ResponseStatus(HttpStatus.OK)
    public AiRestResp<Void> handleAi(AiException e) {
        log.debug("[AI] 业务异常 code={} message={}", e.getErrorCode(), e.getMessage());
        return AiRestResp.error(e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public AiRestResp<Void> handleBusiness(BusinessException e) {
        log.debug("[AI] 宿主业务异常: {}", e.getMessage());
        return AiRestResp.error(300, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public AiRestResp<Void> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return AiRestResp.error(320, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public AiRestResp<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return AiRestResp.error(320, "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public AiRestResp<Void> handleIllegalArgument(IllegalArgumentException e) {
        return AiRestResp.error(320, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public AiRestResp<Void> handleUnexpected(Exception e) {
        log.error("[AI] 未预期异常", e);
        return AiRestResp.error(300, "系统出现错误");
    }
}
