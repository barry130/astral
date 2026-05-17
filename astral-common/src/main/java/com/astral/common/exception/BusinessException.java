package com.astral.common.exception;

import com.astral.common.error.ErrorCodes;

public class BusinessException extends RuntimeException {

    private final String errorCode;
    private final Object[] args;

    public BusinessException(String errorCode) {
        super(ErrorCodes.getMessage(errorCode));
        this.errorCode = errorCode;
        this.args = null;
    }

    public BusinessException(String errorCode, Object... args) {
        super(args.length > 0 ? ErrorCodes.format(errorCode, args) : ErrorCodes.getMessage(errorCode));
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
