package com.astral.common.result;

import com.astral.common.constant.ApiConstants;
import java.io.Serializable;

/**
 * 统一响应结果封装类
 *
 * 所有 RESTful API 的返回值均应使用此类进行包装，确保前后端交互格式一致。
 * 响应体包含状态码（code）、消息（message）、数据（data）和时间戳（timestamp）四个字段。
 *
 * 提供多个静态工厂方法（success / error）方便快速构建响应对象，
 * 避免在 Controller 层手动 new 实例。
 *
 * @param <T> 响应数据的类型，支持泛型以适配不同的业务场景
 */
public class Result<T> implements Serializable {

    /** 业务状态码，200 表示成功，其他值表示各类错误 */
    private int code;

    /** 响应消息，成功或失败的描述信息 */
    private String message;

    /** 响应数据载体，成功时携带业务数据，失败时为 null */
    private T data;

    /** 响应生成时间戳（毫秒），用于客户端调试和日志追踪 */
    private long timestamp;

    /**
     * 无参构造方法
     *
     * 自动记录当前时间戳，供子类或反序列化使用。
     */
    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 全参构造方法
     *
     * @param code    业务状态码
     * @param message 响应消息
     * @param data    响应数据
     */
    public Result(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = System.currentTimeMillis();
    }

    /**
     * 构建成功响应（无数据）
     *
     * 适用于仅需返回成功状态的场景，如删除、更新操作。
     *
     * @param <T> 数据类型
     * @return 成功响应对象，data 为 null
     */
    public static <T> Result<T> success() {
        return new Result<>(ApiConstants.SUCCESS_CODE, ApiConstants.SUCCESS_MSG, null);
    }

    /**
     * 构建成功响应（携带数据）
     *
     * 最常用的成功响应方式，适用于查询、创建等需要返回数据的场景。
     *
     * @param data 业务数据
     * @param <T>  数据类型
     * @return 成功响应对象，包含传入的数据
     */
    public static <T> Result<T> success(T data) {
        return new Result<>(ApiConstants.SUCCESS_CODE, ApiConstants.SUCCESS_MSG, data);
    }

    /**
     * 构建成功响应（自定义消息 + 数据）
     *
     * 适用于需要返回特定成功提示的场景，如 "创建成功"、"导入完成" 等。
     *
     * @param message 自定义成功消息
     * @param data    业务数据
     * @param <T>     数据类型
     * @return 成功响应对象，包含自定义消息和数据
     */
    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ApiConstants.SUCCESS_CODE, message, data);
    }

    /**
     * 构建失败响应（默认错误码 500）
     *
     * 适用于服务器内部异常或不需要细分错误码的场景。
     *
     * @param message 错误描述信息
     * @param <T>     数据类型
     * @return 失败响应对象，data 为 null
     */
    public static <T> Result<T> error(String message) {
        return new Result<>(ApiConstants.ERROR_CODE, message, null);
    }

    /**
     * 构建失败响应（自定义错误码 + 消息）
     *
     * 适用于需要精确返回错误码的场景，如参数校验失败、权限不足等。
     *
     * @param code    业务错误码
     * @param message 错误描述信息
     * @param <T>     数据类型
     * @return 失败响应对象，data 为 null
     */
    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
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
