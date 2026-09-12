package com.astral.qt.common;

/**
 * 轻听插件业务异常
 * <p>携带 HTTP code（默认 300），由全局异常处理器转成 QtRestResp。</p>
 */
public class QtException extends RuntimeException {

    private final Integer code;

    public QtException(String message) {
        super(message);
        this.code = 300;
    }

    public QtException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}