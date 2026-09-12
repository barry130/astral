package com.astral.feedback.common;

/**
 * 反馈插件业务异常
 * <p>携带 code（默认 300），由全局异常处理器转成 FeedbackRestResp。</p>
 */
public class FeedbackException extends RuntimeException {

    private final Integer code;

    public FeedbackException(String message) {
        super(message);
        this.code = 300;
    }

    public FeedbackException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
