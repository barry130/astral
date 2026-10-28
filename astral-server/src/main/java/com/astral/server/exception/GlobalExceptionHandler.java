package com.astral.server.exception;

import com.astral.common.constant.ApiConstants;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.monitor.service.ServerErrorRecorder;
import org.springframework.beans.factory.ObjectProvider;
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
 * 全局异常处理（V2 契约）。
 *
 * <p><b>HTTP 状态码只表达传输层结果，业务结论一律写在 {@code body.code}</b>：</p>
 * <ul>
 *   <li>HTTP 200 = 服务端已处理（成功与业务失败都是 200，如业务异常、参数错误、未登录、无权限、限流）；</li>
 *   <li>HTTP 404 = 路由不存在；405 = 方法不支持；413 = 上传超限；
 *       500 = 未预期异常；503 = 服务不可用（{@link IllegalStateException}）。</li>
 * </ul>
 *
 * <p>{@code body.code} 是业务码：200 成功 / 300 业务失败 / 320 参数错误 / 401 未登录 /
 * 403 无权限 / 429 限流 / 500 系统错误；{@code body.errorCode} 保留细分业务码
 * （{@code AUTH001}、{@code COMMON002}…），{@code body.msg} 为人类可读消息。</p>
 *
 * <p><b>关于信息泄露</b>：未知异常一律返回 {@code COMMON001} 的固定文案，
 * 绝不把异常消息、堆栈、SQL 片段回传客户端；详细信息只进服务端日志。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常默认业务码：业务规则被违反，但服务端已正常处理 */
    private static final int DEFAULT_BUSINESS_CODE = ApiConstants.BUSINESS_ERROR_CODE;

    /**
     * 服务端异常登记器（可选注入：astral.stat.enabled=false 时无该 Bean）。
     * 兜底 500 异步登记进 stat_error_log(source=server)，管理台「错误统计」
     * 与告警规则（SERVER_ERROR_COUNT）才能看见后端自身的故障。
     */
    private final ObjectProvider<ServerErrorRecorder> serverErrorRecorder;

    public GlobalExceptionHandler(ObjectProvider<ServerErrorRecorder> serverErrorRecorder) {
        this.serverErrorRecorder = serverErrorRecorder;
    }

    /**
     * 业务异常：HTTP 200（服务端已处理），body.code 按 errorCode 语义映射。
     *
     * @see #mapBusinessCode(String)
     */
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        int code = mapBusinessCode(e.getErrorCode());
        Result<?> body;
        if (e.getErrorCode() != null) {
            log.warn("Business exception: errorCode={}, message={}", e.getErrorCode(), e.getMessage());
            body = Result.errorRaw(e.getErrorCode(), e.getMessage());
        } else {
            log.warn("Business exception: message={}", e.getMessage());
            body = Result.fail(e.getMessage());
        }
        body.setCode(code);
        return body;
    }

    /**
     * 把细分业务错误码映射为 body 业务码。
     *
     * <p>只对「语义明确的少数几类」做特殊映射，其余一律 300（业务失败）——
     * 不做过度设计，但保证 401/403/429 这三个最影响客户端行为的业务码是对的。</p>
     *
     * @param errorCode 业务错误码，可为 null
     * @return 对应的业务码（401 / 403 / 429 / 300）
     */
    private int mapBusinessCode(String errorCode) {
        if (errorCode == null) {
            return DEFAULT_BUSINESS_CODE;
        }
        return switch (errorCode) {
            // 未登录 / 凭据错误 / Token 失效（AUTH010/011 = 登录需补 TOTP 动态码，同属凭据未齐）
            case "AUTH001", "AUTH002", "AUTH004", "AUTH005", "AUTH006", "AUTH007", "AUTH010", "AUTH011" ->
                    ApiConstants.UNAUTHORIZED_CODE;
            // 账号锁定 / 限流类：语义上属于「稍后再试」
            case "AUTH003", "AUTH008", "STORAGE007", "STORAGE026" -> ApiConstants.TOO_MANY_REQUESTS_CODE;
            // 权限不足
            case "COMMON005", "STORAGE013", "STORAGE014", "STORAGE015", "STORAGE018" -> ApiConstants.FORBIDDEN_CODE;
            // 其余业务失败（含「数据不存在」「冲突」等：服务端已处理，属业务结论而非传输失败）
            default -> DEFAULT_BUSINESS_CODE;
        };
    }

    /** 参数校验失败：HTTP 200 + 320 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<?> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Validation failed: {}", message);
        Result<?> body = Result.error("COMMON002", message);
        body.setCode(ApiConstants.PARAM_ERROR_CODE);
        return body;
    }

    /** 参数绑定失败：HTTP 200 + 320 */
    @ExceptionHandler(BindException.class)
    public Result<?> handleBindException(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.warn("Bind validation failed: {}", message);
        Result<?> body = Result.error("COMMON002", message);
        body.setCode(ApiConstants.PARAM_ERROR_CODE);
        return body;
    }

    /** 缺少必填参数：HTTP 200 + 320 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<?> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("Missing request parameter: {}", e.getParameterName());
        Result<?> body = Result.error("COMMON004", e.getParameterName());
        body.setCode(ApiConstants.PARAM_ERROR_CODE);
        return body;
    }

    /** 请求体不可解析：HTTP 200 + 320（只记日志，不回传解析器细节） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("Malformed request body: {}", e.getMessage());
        Result<?> body = Result.error("COMMON002", "请求体格式错误，请检查JSON格式");
        body.setCode(ApiConstants.PARAM_ERROR_CODE);
        return body;
    }

    /** 参数类型不匹配：HTTP 200 + 320 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<?> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("Parameter type mismatch: name={}, value={}", e.getName(), e.getValue());
        Result<?> body = Result.error("COMMON002", "参数'" + e.getName() + "'类型不匹配");
        body.setCode(ApiConstants.PARAM_ERROR_CODE);
        return body;
    }

    /** 方法不支持：真实的 HTTP 405 + body.code 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<?>> handleHttpRequestMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("Method not supported: {}", e.getMessage());
        Result<?> body = Result.error("COMMON008", e.getMethod());
        body.setCode(HttpStatus.METHOD_NOT_ALLOWED.value());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    /**
     * 上传超限：真实的 HTTP 413 + body.code 413。
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
     * 服务不可用：真实的 HTTP 503 + body.code 503。
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

    /** 参数非法：{@code IllegalArgumentException} 多为标识符/取值校验失败，HTTP 200 + 320 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("Illegal argument: {}", e.getMessage());
        Result<?> body = Result.error("COMMON002", e.getMessage());
        body.setCode(ApiConstants.PARAM_ERROR_CODE);
        return body;
    }

    /** 路由不存在：真实的 HTTP 404 + body.code 404 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Result<?>> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("Resource not found: {}", e.getMessage());
        Result<?> body = Result.error("COMMON003");
        body.setCode(HttpStatus.NOT_FOUND.value());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 兜底：固定文案 + 真实 HTTP 500 + body.code 500，绝不回传异常细节 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<?>> handleException(Exception e, jakarta.servlet.http.HttpServletRequest request) {
        log.error("Unexpected error", e);
        // 登记（异步、绝不抛出）：page 维度记 METHOD + URI，供错误统计页与告警规则消费
        try {
            ServerErrorRecorder recorder = serverErrorRecorder.getIfAvailable();
            if (recorder != null) {
                recorder.recordAsync(e, request.getRequestURI(), request.getMethod());
            }
        } catch (Exception ignored) {
            // 登记链路自身的任何异常都不影响错误响应
        }
        Result<?> body = Result.error("COMMON001");
        body.setCode(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
