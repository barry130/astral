package com.astral.feedback.config;

import com.astral.common.exception.BusinessException;
import com.astral.feedback.common.FeedbackException;
import com.astral.feedback.common.FeedbackRestResp;
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
 * 反馈插件统一异常处理
 * <p>仅作用于 com.astral.feedback.controller 下的 App 控制器，返回 {code,msg,data}。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(assignableTypes = {
        com.astral.feedback.controller.AppFeedbackController.class,
        com.astral.feedback.controller.AppMessageController.class
})
public class FeedbackGlobalExceptionHandler {

    @ExceptionHandler(FeedbackException.class)
    @ResponseStatus(HttpStatus.OK)
    public FeedbackRestResp<Void> handleFeedbackException(FeedbackException e) {
        log.warn("[FeedbackPlugin] 业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return FeedbackRestResp.error(e.getCode(), e.getMessage());
    }

    /** 宿主业务异常：透传语义，以插件包装返回 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public FeedbackRestResp<Void> handleBusinessException(BusinessException e) {
        log.warn("[FeedbackPlugin] 拦截器业务异常 code={}, msg={}", e.getErrorCode(), e.getMessage());
        return FeedbackRestResp.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.OK)
    public FeedbackRestResp<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return FeedbackRestResp.error(320, msg);
    }

    @ExceptionHandler(BindException.class)
    @ResponseStatus(HttpStatus.OK)
    public FeedbackRestResp<Void> handleBind(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().isEmpty()
                ? "参数校验失败" : e.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        return FeedbackRestResp.error(320, msg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public FeedbackRestResp<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return FeedbackRestResp.error(320, "缺少请求参数: " + e.getParameterName());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public FeedbackRestResp<Void> handleException(Exception e) {
        log.error("[FeedbackPlugin] 系统异常", e);
        return FeedbackRestResp.error(300, "系统出现错误");
    }
}
