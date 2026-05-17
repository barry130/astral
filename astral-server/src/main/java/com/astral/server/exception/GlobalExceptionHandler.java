package com.astral.server.exception;

import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 * <p>统一处理Controller层抛出的各类异常，返回标准化的错误响应</p>
 * <p>按照异常类型返回不同的HTTP状态码和错误信息</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     * <p>业务逻辑中主动抛出的异常，通常带有明确的错误码和错误信息</p>
     *
     * @param e 业务异常
     * @return 错误响应（使用异常自带的错误码和消息）
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return Result.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常
     * <p>当@Valid/@Validated注解的参数校验失败时抛出</p>
     *
     * @param e 参数校验异常
     * @return 错误响应（HTTP 400，包含所有校验失败信息的拼接）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        // 提取所有字段的校验错误信息，用分号拼接
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        return Result.error(400, message);
    }

    /**
     * 处理非法状态异常
     * <p>服务不可用时抛出，如集群未启用等场景</p>
     *
     * @param e 非法状态异常
     * @return 错误响应（HTTP 503）
     */
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<?> handleIllegalState(IllegalStateException e) {
        log.error("Service unavailable: {}", e.getMessage());
        return Result.error(503, e.getMessage());
    }

    /**
     * 处理未知异常
     * <p>兜底处理器，捕获所有未单独处理的Exception类型</p>
     *
     * @param e 异常
     * @return 错误响应（HTTP 500，通用错误信息）
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<?> handleException(Exception e) {
        log.error("Unexpected error", e);
        return Result.error(500, "Internal server error");
    }
}
