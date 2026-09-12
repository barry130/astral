package com.astral.qt.common;

import lombok.Getter;

/**
 * 轻听 API 统一响应
 * <p>与参考后端 qt 的 RestResp 对齐：{@code {code, msg, data}}。</p>
 * code 业务约定与前端一致：200 成功；401 token失效（前端据此清除登录态）。
 */
@Getter
public class QtRestResp<T> {

    private final Integer code;
    private final String msg;
    private T data;

    private QtRestResp() {
        this.code = 200;
        this.msg = "success";
    }

    private QtRestResp(T data) {
        this.code = 200;
        this.msg = "success";
        this.data = data;
    }

    private QtRestResp(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public static QtRestResp<Void> success() {
        return new QtRestResp<>();
    }

    public static <T> QtRestResp<T> success(T data) {
        return new QtRestResp<>(data);
    }

    public static QtRestResp<Void> error(String msg) {
        return new QtRestResp<>(300, msg);
    }

    public static <T> QtRestResp<T> error(Integer code, String msg) {
        return new QtRestResp<>(code, msg);
    }
}