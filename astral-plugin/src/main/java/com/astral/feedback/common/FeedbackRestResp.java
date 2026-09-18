package com.astral.feedback.common;

import lombok.Getter;

/**
 * 反馈插件 App 端统一响应
 * <p>与轻听 QtRestResp 同构：{@code {code, msg, data}}，兼容 App 端 http.ts 解析逻辑。</p>
 * code 约定：200 成功；401 token失效；300 业务错误；320 参数错误。
 */
@Getter
public class FeedbackRestResp<T> {

    private final Integer code;
    private final String msg;
    private T data;

    private FeedbackRestResp() {
        this.code = 200;
        this.msg = "success";
    }

    private FeedbackRestResp(T data) {
        this.code = 200;
        this.msg = "success";
        this.data = data;
    }

    private FeedbackRestResp(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static FeedbackRestResp<Void> success() {
        return new FeedbackRestResp<>();
    }

    public static <T> FeedbackRestResp<T> success(T data) {
        return new FeedbackRestResp<>(data);
    }

    public static FeedbackRestResp<Void> error(String msg) {
        return new FeedbackRestResp<>(300, msg);
    }

    public static <T> FeedbackRestResp<T> error(Integer code, String msg) {
        return new FeedbackRestResp<>(code, msg);
    }
}
