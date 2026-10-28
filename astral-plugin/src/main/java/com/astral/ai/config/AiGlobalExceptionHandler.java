package com.astral.ai.config;

import com.astral.ai.common.AiException;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
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
 * <p>V2 契约：HTTP 恒 200（表示服务端已处理），成败只由 body 的 code 表达
 * （200 成功 / 300 业务失败 / 320 参数错误 / 401 未登录 / 403 无权限 / 500 系统错误），
 * body.errorCode 保留细分码。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(basePackages = "com.astral.ai.controller")
public class AiGlobalExceptionHandler {

    @ExceptionHandler(AiException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleAi(AiException e) {
        log.debug("[AI] 业务异常 code={} message={}", e.getErrorCode(), e.getMessage());
        // V2 契约：业务失败统一 code=300，errorCode（如 AI001）保留细分语义
        return Result.error(300, e.getErrorCode(), e.getMessage());
    }

    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusiness(BusinessException e) {
        log.debug("[AI] 宿主业务异常: {}", e.getMessage());
        return Result.error(300, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleInvalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数不合法");
        return Result.error(320, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error(320, "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        return Result.error(320, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("[AI] 未预期异常", e);
        return Result.error(500, "系统出现错误");
    }
}
