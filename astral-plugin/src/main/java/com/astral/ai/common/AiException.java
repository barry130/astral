package com.astral.ai.common;

import com.astral.common.error.ErrorCodes;

/**
 * AI 模块业务异常。
 *
 * <p>错误码统一走 {@link ErrorCodes}（{@code error-codes.properties} 中的 AI001–AI015），
 * 由 {@code AiGlobalExceptionHandler} 统一转换响应，不再各自拼消息。</p>
 */
public class AiException extends RuntimeException {

    private final String errorCode;
    private final Object[] args;

    public AiException(String errorCode) {
        this(errorCode, new Object[0]);
    }

    public AiException(String errorCode, Object... args) {
        super(ErrorCodes.format(errorCode, args));
        this.errorCode = errorCode;
        this.args = args;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object[] getArgs() {
        return args;
    }
}
