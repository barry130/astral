package com.astral.common.exception;

import com.astral.common.constant.ApiConstants;
import com.astral.common.error.ErrorCodes;

/**
 * 业务异常：控制器与服务层表达「业务规则不满足」的唯一手段。
 *
 * <p>两种用法：</p>
 * <ul>
 *   <li>{@link #BusinessException(String)} / {@link #BusinessException(String, Object...)}：
 *       携带错误码，文案从 {@code error-codes.properties} 取，业务码由全局异常处理器按错误码映射。</li>
 *   <li>{@link #of(String)} / {@link #of(int, String)}：直接携带最终展示文案，
 *       用于没有错误码、或需要显式指定业务码的场景（320 参数错误、321 自定义码等）。</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final Object[] args;
    /** 显式业务码；为 null 表示由 errorCode 映射得出 */
    private final Integer code;

    public BusinessException(String errorCode) {
        super(ErrorCodes.getMessage(errorCode));
        this.errorCode = errorCode;
        this.args = null;
        this.code = null;
    }

    public BusinessException(String errorCode, Object... args) {
        super(args.length > 0 ? ErrorCodes.format(errorCode, args) : ErrorCodes.getMessage(errorCode));
        this.errorCode = errorCode;
        this.args = args;
        this.code = null;
    }

    private BusinessException(String errorCode, Object[] args, String message, Integer code) {
        super(message);
        this.errorCode = errorCode;
        this.args = args;
        this.code = code;
    }

    /** 直接携带展示文案的业务失败，业务码为 300，不带 errorCode */
    public static BusinessException of(String message) {
        return new BusinessException(null, null, message, ApiConstants.BUSINESS_ERROR_CODE);
    }

    /** 直接携带展示文案 + 显式业务码（320 参数错误、321 自定义码等），不带 errorCode */
    public static BusinessException of(int code, String message) {
        return new BusinessException(null, null, message, code);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object[] getArgs() {
        return args;
    }

    /** 显式业务码；为 null 时由全局异常处理器根据 {@link #getErrorCode()} 映射 */
    public Integer getCode() {
        return code;
    }
}
