package com.astral.server.exception;

import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理。
 *
 * <p><b>关于 HTTP 状态码</b>：早期实现里所有异常都返回 HTTP 200，只靠 body 里的
 * {@code code} 区分成败。这会让网关、APM、监控告警、客户端重试策略全部失效
 * （false-green）——一个 100% 报错的接口在监控上看起来完全健康。</p>
 *
 * <p>现在按异常语义返回真实状态码，同时<b>保持响应体结构不变</b>
 * （仍是 {@code Result}，含 {@code code/errorCode/message}），
 * 前端 axios 拦截器既读 body 也读 HTTP 状态，因此是兼容的。</p>
 *
 * <p><b>关于信息泄露</b>：未知异常一律返回 {@code COMMON001} 的固定文案，
 * 绝不把异常消息、堆栈、SQL 片段回传客户端；详细信息只进服务端日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常默认状态码：业务规则被违反属于客户端问题 */
    private static final HttpStatus DEFAULT_BUSINESS_STATUS = HttpStatus.BAD_REQUEST;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<?>> handleBusinessException(BusinessException e) {
        HttpStatus status = mapBusinessStatus(e.getErrorCode());
        Result<?> body;
        if (e.getErrorCode() != null) {
            log.warn("Business exception: errorCode={}, message={}", e.getErrorCode(), e.getMessage());
            body = Result.errorRaw(e.getErrorCode(), e.getMessage());
        } else {
            log.warn("Business exception: message={}", e.getMessage());
            body = Result.fail(e.getMessage());
        }
        // body.code 与 HTTP 状态码保持一致，避免两套语义打架
        body.setCode(status.value());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * 把业务错误码映射为 HTTP 状态码。
     *
     * <p>只对「语义明确的少数几类」做特殊映射，其余一律 400——
     * 不做过度设计，但保证 401/403 这两个最影响链路行为的状态码是对的。</p>
     *
     * @param errorCode 业务错误码，可为 null
     * @return 对应的 HTTP 状态码
     */
    private HttpStatus mapBusinessStatus(String errorCode) {
        if (errorCode == null) {
            return DEFAULT_BUSINESS_STATUS;
        }
        return switch (errorCode) {
            // 未登录 / 凭据错误 / Token 失效
            case "AUTH001", "AUTH002", "AUTH004", "AUTH005", "AUTH006", "AUTH007" -> HttpStatus.UNAUTHORIZED;
            // 账号锁定 / 限流类：语义上属于「稍后再试」
            case "AUTH003", "AUTH008", "STORAGE007", "STORAGE026" -> HttpStatus.TOO_MANY_REQUESTS;
            // 权限不足
            case "COMMON005", "STORAGE013", "STORAGE014", "STORAGE015", "STORAGE018" -> HttpStatus.FORBIDDEN;
            // 数据不存在
            case "COMMON003", "SYS001", "SYS003", "SYS005", "SYS007", "SYS008", "SYS009",
                 "SEQ001", "SEQ007", "MAIL001", "MAIL002", "STORAGE002", "STORAGE004",
                 "STORAGE016", "STORAGE025" -> HttpStatus.NOT_FOUND;
            // 冲突：已存在 / 被引用
            case "SYS002", "SYS004", "SYS006", "SYS011", "SYS012", "SEQ003", "STORAGE028" -> HttpStatus.CONFLICT;
            default -> DEFAULT_BUSINESS_STATUS;
        };
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<?>> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        Result<?> body = Result.error("COMMON002", message);
        body.setCode(HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<Result<?>> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Bind validation failed: {}", message);
        Result<?> body = Result.error("COMMON002", message);
        body.setCode(HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Result<?>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        Result<?> body = Result.error("COMMON004", e.getParameterName());
        body.setCode(HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<?>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        // 只记日志，不回传解析器细节（可能包含内部类名/字段名）
        log.warn("Malformed request body: {}", e.getMessage());
        Result<?> body = Result.error("COMMON002", "请求体格式错误，请检查JSON格式");
        body.setCode(HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<?>> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Parameter type mismatch: name={}, value={}", e.getName(), e.getValue());
        Result<?> body = Result.error("COMMON002", "参数'" + e.getName() + "'类型不匹配");
        body.setCode(HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not supported: {}", e.getMessage());
        Result<?> body = Result.error("COMMON005", "不支持的请求方法: " + e.getMethod());
        body.setCode(HttpStatus.METHOD_NOT_ALLOWED.value());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    /**
     * 上传超限。
     *
     * <p>此前没有专门处理，会落到 {@code Exception} 兜底，用户只看到「系统繁忙」，
     * 完全不知道是文件太大。{@code spring.servlet.multipart.max-file-size} 已配置为 50MB。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<?>> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("Upload size exceeded: {}", e.getMessage());
        Result<?> body = Result.error("COMMON006");
        body.setCode(HttpStatus.PAYLOAD_TOO_LARGE.value());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }

    /**
     * 服务不可用。
     *
     * <p><b>不回传 {@code e.getMessage()}</b>：{@code IllegalStateException} 的消息
     * 常含内部实现细节（连接串、号段范围、状态机取值），原实现用
     * {@code Result.errorRaw("COMMON001", e.getMessage())} 直接透传，属于信息泄露。
     * 详情只写服务端日志。</p>
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Result<?>> handleIllegalState(IllegalStateException e) {
        log.error("Service unavailable: {}", e.getMessage(), e);
        Result<?> body = Result.error("COMMON001");
        body.setCode(HttpStatus.SERVICE_UNAVAILABLE.value());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** 参数非法：{@code IllegalArgumentException} 多为标识符/取值校验失败，属客户端问题 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<?>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        Result<?> body = Result.error("COMMON002", e.getMessage());
        body.setCode(HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("Resource not found: {}", e.getMessage());
        Result<?> body = Result.error("COMMON003");
        body.setCode(HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 兜底：固定文案 + 500，绝不回传异常细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleException(Exception e) {
        log.error("Unexpected error", e);
        Result<?> body = Result.error("COMMON001");
        body.setCode(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
