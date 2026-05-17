package com.astral.common.exception;

import com.astral.common.constant.ErrorCode;

/**
 * 业务异常类
 *
 * 系统中最常用的自定义异常，用于封装业务逻辑中可预期的错误场景。
 * 携带错误码（code）和错误消息（message），可被全局异常处理器捕获
 * 并转换为统一的 Result 响应格式返回给前端。
 *
 * 继承 RuntimeException，支持在业务层直接抛出而无需显式声明 throws。
 */
public class BusinessException extends RuntimeException {

    /** 业务错误码，用于前端区分不同的错误类型 */
    private final int code;

    /**
     * 构造方法 - 仅指定错误消息
     *
     * 默认错误码为 500（通用服务器错误），适用于不需要细分错误码的场景。
     *
     * @param message 错误描述信息
     */
    public BusinessException(String message) {
        super(message);
        this.code = 500;
    }

    /**
     * 构造方法 - 指定错误码和错误消息
     *
     * 适用于需要精确控制返回错误码的业务场景。
     *
     * @param code    业务错误码
     * @param message 错误描述信息
     */
    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 构造方法 - 从 ErrorCode 接口实例构建
     *
     * 推荐使用的方式，通过实现 ErrorCode 接口的枚举或常量类来创建异常，
     * 保证错误码和消息的一致性，便于集中管理。
     *
     * @param errorCode 错误码接口实例，包含 code 和 message
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 获取业务错误码
     *
     * @return 数字型错误码
     */
    public int getCode() {
        return code;
    }
}
