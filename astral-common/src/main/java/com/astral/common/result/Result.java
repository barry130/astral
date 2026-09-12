package com.astral.common.result;

import com.astral.common.constant.ApiConstants;
import com.astral.common.error.ErrorCodes;
import java.io.Serializable;

public class Result<T> implements Serializable {

    private int code;
    private String errorCode;
    private String message;
    private T data;
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public Result(int code, String errorCode, String message, T data) {
        this.code = code;
        this.errorCode = errorCode;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success() {
        return new Result<>(ApiConstants.SUCCESS_CODE, ApiConstants.SUCCESS_MSG, null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ApiConstants.SUCCESS_CODE, ApiConstants.SUCCESS_MSG, data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ApiConstants.SUCCESS_CODE, message, data);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(ApiConstants.ERROR_CODE, null, message, null);
    }

    public static <T> Result<T> error(String errorCode, Object... args) {
        return new Result<>(ApiConstants.ERROR_CODE, errorCode, ErrorCodes.format(errorCode, args), null);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    /**
     * 使用最终消息构造错误结果（不再做占位符格式化）
     * <p>
     * 适用于消息已经过 BusinessException 格式化的场景，避免二次格式化导致消息重复拼接。
     * </p>
     */
    public static <T> Result<T> errorRaw(String errorCode, String message) {
        return new Result<>(ApiConstants.ERROR_CODE, errorCode, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
