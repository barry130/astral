package com.astral.feedback.config;

import com.astral.common.result.Result;
import com.astral.feedback.common.FeedbackException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 反馈插件管理端统一异常处理
 * <p>仅作用于两个管理端控制器（AdminFeedbackController / AdminMessageController），
 * 返回宿主 {@link Result} 包装，与系统管理端接口风格一致。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(assignableTypes = {
        com.astral.feedback.controller.AdminFeedbackController.class,
        com.astral.feedback.controller.AdminMessageController.class
})
public class FeedbackAdminExceptionHandler {

    @ExceptionHandler(FeedbackException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleFeedbackException(FeedbackException e) {
        log.warn("[FeedbackPlugin] 管理端业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.error(400, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return Result.error(400, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error(400, "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("[FeedbackPlugin] 管理端系统异常", e);
        return Result.error(500, "系统出现错误");
    }
}
