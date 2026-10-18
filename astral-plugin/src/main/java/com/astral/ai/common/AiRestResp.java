package com.astral.ai.common;

import lombok.Data;

/**
 * AI 插件统一响应体。
 *
 * <p>与 {@code FeedbackRestResp} / {@code QtRestResp} 结构一致：{@code code=200} 表示成功。
 * 插件内不直接复用宿主 {@code Result}，避免管理端与 App 端的错误码空间互相污染。</p>
 *
 * @param <T> 数据类型
 */
@Data
public class AiRestResp<T> {

    /** 状态码：200 成功，其它为业务错误 */
    private int code;

    /** 业务错误码（如 AI004），成功时为 null */
    private String errorCode;

    /** 提示信息 */
    private String message;

    /** 数据 */
    private T data;

    /** 时间戳 */
    private long timestamp = System.currentTimeMillis();

    public static <T> AiRestResp<T> ok(T data) {
        AiRestResp<T> resp = new AiRestResp<>();
        resp.code = 200;
        resp.message = "ok";
        resp.data = data;
        return resp;
    }

    public static <T> AiRestResp<T> ok() {
        return ok(null);
    }

    public static <T> AiRestResp<T> error(int code, String message) {
        AiRestResp<T> resp = new AiRestResp<>();
        resp.code = code;
        resp.message = message;
        return resp;
    }

    public static <T> AiRestResp<T> error(String errorCode, String message) {
        AiRestResp<T> resp = new AiRestResp<>();
        resp.code = 300;
        resp.errorCode = errorCode;
        resp.message = message;
        return resp;
    }
}
