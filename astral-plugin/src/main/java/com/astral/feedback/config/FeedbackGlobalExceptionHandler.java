package com.astral.feedback.config;

import com.astral.common.exception.BusinessException;
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
 * 反馈插件统一异常处理
 * <p>仅作用于 com.astral.feedback.controller 下的 App 控制器，返回 {code,msg,data}。</p>
 * <p>通知的 App 端接口已随通知能力收归宿主（astral-system 的 AppNoticeController），
 * 其异常由宿主的 {@code NoticeAppExceptionHandler} 处理，<b>不要</b>在这里再加它的
 * assignableTypes —— 跨模块引用控制器会让插件反向依赖宿主实现细节。</p>
 * <p>V2 契约：HTTP 恒 200（表示服务端已处理），成败只由 body 的 code 表达
 * （200 成功 / 300 业务失败 / 320 参数错误 / 401 未登录 / 403 无权限 / 500 系统错误），
 * body.errorCode 保留细分码。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(assignableTypes = {
        com.astral.feedback.controller.AppFeedbackController.class
})
public class FeedbackGlobalExceptionHandler {

    @ExceptionHandler(FeedbackException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleFeedbackException(FeedbackException e) {
        log.warn("[FeedbackPlugin] 业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /** 宿主业务异常：透传语义，以插件包装返回 */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("[FeedbackPlugin] 拦截器业务异常 code={}, msg={}", e.getErrorCode(), e.getMessage());
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
        log.error("[FeedbackPlugin] 系统异常", e);
        return Result.error(500, "系统出现错误");
    }
}
