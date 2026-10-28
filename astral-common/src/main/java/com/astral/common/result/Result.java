package com.astral.common.result;

import com.astral.common.constant.ApiConstants;
import com.astral.common.error.ErrorCodes;
import java.io.Serializable;

/**
 * 统一响应体：全仓（管理端 + App 端 + 各插件）唯一实现。
 *
 * <p>线上契约：{@code {code, errorCode?, msg, data, timestamp}}。App 客户端历来读 {@code msg}，
 * 因此字段名固定为 {@code msg}（管理端前端同步读 {@code msg}）。</p>
 *
 * <p><b>传输层与业务码分离（V2 契约）</b>：</p>
 * <ul>
 *   <li>HTTP 状态码只表达传输层结果：200 = 服务端已处理（成功与业务失败都是 200）、
 *       404 路由不存在、405 方法不支持、413 上传超限、500 未预期异常、503 服务不可用；</li>
 *   <li>{@code code} 是<b>业务码</b>：200 成功 / 300 业务失败 / 320 参数错误 / 401 未登录 /
 *       403 无权限 / 429 限流 / 500 系统错误。客户端一律以本字段判定业务成败；</li>
 *   <li>{@code errorCode} 保留细分业务码（{@code AUTH001}、{@code COMMON002}…），{@code msg} 为人类可读消息。</li>
 * </ul>
 *
 * <p>各插件历史上的响应封装（{@code error(String)} 默认码为 300）已全部收敛到本类，
 * 因此本类的默认错误码同为 {@link ApiConstants#BUSINESS_ERROR_CODE}（300），行为与旧封装一致；
 * 需要别的业务语义（参数错误 401/403/429…）时显式调用 {@code error(int code, ...)}。</p>
 */
public class Result<T> implements Serializable {

    private int code;
    private String errorCode;
    private String msg;
    private T data;
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public Result(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public Result(int code, String errorCode, String msg, T data) {
        this.code = code;
        this.errorCode = errorCode;
        this.msg = msg;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success() {
        return new Result<>(ApiConstants.SUCCESS_CODE, ApiConstants.SUCCESS_MSG, null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ApiConstants.SUCCESS_CODE, ApiConstants.SUCCESS_MSG, data);
    }

    public static <T> Result<T> success(String msg, T data) {
        return new Result<>(ApiConstants.SUCCESS_CODE, msg, data);
    }

    /**
     * 业务失败（无细分业务码），业务码为 {@link ApiConstants#BUSINESS_ERROR_CODE}（300）。
     *
     * <p>「服务端已处理但业务规则拒绝」统一走本方法；只有未预期异常才是
     * {@link ApiConstants#ERROR_CODE}（500），由全局异常处理器兜底写入。</p>
     */
    public static <T> Result<T> fail(String msg) {
        return new Result<>(ApiConstants.BUSINESS_ERROR_CODE, null, msg, null);
    }

    /** 业务失败 + 细分业务码，业务码为 {@link ApiConstants#BUSINESS_ERROR_CODE}（300） */
    public static <T> Result<T> error(String errorCode, Object... args) {
        return new Result<>(ApiConstants.BUSINESS_ERROR_CODE, errorCode, ErrorCodes.format(errorCode, args), null);
    }

    /** 指定业务码 + 消息 */
    public static <T> Result<T> error(int code, String msg) {
        return new Result<>(code, msg, null);
    }

    /**
     * 使用最终消息构造错误结果（不再做占位符格式化），业务码为
     * {@link ApiConstants#BUSINESS_ERROR_CODE}（300）
     * <p>
     * 适用于消息已经过 BusinessException 格式化的场景，避免二次格式化导致消息重复拼接。
     * </p>
     */
    public static <T> Result<T> errorRaw(String errorCode, String msg) {
        return new Result<>(ApiConstants.BUSINESS_ERROR_CODE, errorCode, msg, null);
    }

    /**
     * 指定业务码 + 业务 errorCode + 最终消息
     * <p>
     * 需要「参数错误 320 / 未登录 401 / 无权限 403 / 限流 429 / 系统错误 500」
     * 这类细分语义时用本方法显式传码；普通业务失败直接用默认 300。
     * </p>
     */
    public static <T> Result<T> error(int code, String errorCode, String msg) {
        return new Result<>(code, errorCode, msg, null);
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

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
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
