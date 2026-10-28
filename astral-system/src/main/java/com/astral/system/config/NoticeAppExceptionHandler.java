package com.astral.system.config;

import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.system.notice.controller.AppNoticeController;
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
 * 通知 App 端异常处理：把异常转成统一响应体 {@link Result}（{@code {code,errorCode?,msg,data,timestamp}}）。
 *
 * <p><b>为什么仍需要单独一个 advice</b>：响应体类已全仓收敛为 {@link Result}，但<b>传输层分端不变</b>——
 * 宿主 {@code GlobalExceptionHandler} 走管理端语义（HTTP 状态码复述业务码），而 App 客户端只读 body 的
 * {@code code}、非 200 会被 axios 当网络错误，因此 App 端接口的异常必须由本类接住并恒返回 HTTP 200。</p>
 *
 * <p><b>两个 advice 同时存在时的优先级</b>：宿主 {@code GlobalExceptionHandler} 没有
 * {@code @Order}（= {@code LOWEST_PRECEDENCE}），本类显式取
 * {@code HIGHEST_PRECEDENCE + 20}，因此对 {@link AppNoticeController} 的异常
 * 由本类先接住，其它控制器不受影响（{@code assignableTypes} 限定）。</p>
 *
 * <p><b>HTTP 状态码一律 200</b>：App 端历史约定只读 body 的 {@code code}
 * （401 = 登录失效、320 = 参数错误、300 = 业务错误），HTTP 层非 200 会被客户端
 * axios 拦截器当作网络错误处理。</p>
 *
 * <p><b>V2 契约</b>：HTTP 恒 200（表示服务端已处理），成败只由 body 的 code 表达
 * （200 成功 / 300 业务失败 / 320 参数错误 / 401 未登录 / 403 无权限 / 500 系统错误），
 * body.errorCode 保留细分码。</p>
 */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@RestControllerAdvice(assignableTypes = {AppNoticeController.class})
public class NoticeAppExceptionHandler {

    /** 宿主业务异常：透传语义，以 App 包装返回（code 300，与原插件版一致） */
    @ExceptionHandler(BusinessException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBusinessException(BusinessException e) {
        log.warn("[Notice] App 端业务异常 code={}, msg={}", e.getErrorCode(), e.getMessage());
        return Result.error(300, e.getMessage());
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

    /**
     * 未登录兜底：{@code AppNoticeController} 在拦截器漏放行时抛 {@link IllegalStateException}。
     * <p>理论不可达（拦截器先拒），但契约必须与插件版一致：{@code {code:401,msg:"登录状态已失效"}}，
     * 不能落到下面的通用 {@code 500} 分支。</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleIllegalState(IllegalStateException e) {
        log.warn("[Notice] App 端未登录/状态异常: {}", e.getMessage());
        return Result.error(401, "登录状态已失效");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleException(Exception e) {
        log.error("[Notice] App 端系统异常", e);
        return Result.error(500, "系统出现错误");
    }
}
