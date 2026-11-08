package com.astral.common.constant;

/**
 * 错误码接口
 *
 * 定义统一的错误码契约，所有业务错误码枚举或常量类都应实现此接口，
 * 确保错误码（code）和错误描述（message）的获取方式一致，
 * 便于全局异常处理器统一解析和返回。
 */
public interface ErrorCode {

    /**
     * 获取错误码
     *
     * @return 数字型错误码，用于前端或调用方进行逻辑判断
     */
    int getCode();

    /**
     * 获取错误描述信息
     *
     * @return 错误消息字符串，用于展示给用户或记录日志
     */
    String getMessage();
}
