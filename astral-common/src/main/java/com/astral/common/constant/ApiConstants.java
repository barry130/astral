package com.astral.common.constant;

/**
 * API 常量定义类
 *
 * 集中管理项目中所有 API 相关的常量，包括 URL 前缀、HTTP 状态码、
 * 默认成功/失败消息等，避免在代码中硬编码魔法值。
 */
public class ApiConstants {

    /** API 统一版本前缀，所有 RESTful 接口均以此路径开头 */
    public static final String API_PREFIX = "/api/v1";

    /** 业务成功状态码，表示请求处理成功 */
    public static final int SUCCESS_CODE = 200;

    /** 业务失败状态码，表示服务器内部异常 */
    public static final int ERROR_CODE = 500;

    /** 默认成功响应消息 */
    public static final String SUCCESS_MSG = "success";

    /** 默认失败响应消息 */
    public static final String ERROR_MSG = "error";
}
