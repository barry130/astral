package com.astral.common.constant;

/**
 * API 常量定义类
 *
 * 集中管理项目中所有 API 相关的常量，包括 URL 前缀、响应体业务码、
 * 默认成功/失败消息等，避免在代码中硬编码魔法值。
 *
 * <p><b>传输层与业务码分离（V2 契约）</b>：HTTP 状态码只表达传输层结果
 * （200 = 服务端已处理，含业务失败；404 路由不存在；405 方法不支持；413 上传超限；
 * 500 未预期异常；503 服务不可用），而 {@code body.code} 是<b>业务码</b>，
 * 表达这次请求的业务结论。两者互不复述，客户端一律以 {@code body.code} 判定业务成败。</p>
 */
public class ApiConstants {

    /** API 统一版本前缀，所有 RESTful 接口均以此路径开头 */
    public static final String API_PREFIX = "/api/v1";

    /** 业务成功码，表示请求处理成功 */
    public static final int SUCCESS_CODE = 200;

    /** 系统错误码：未预期异常（与 HTTP 500 语义一致，由全局异常处理器兜底写入） */
    public static final int ERROR_CODE = 500;

    /** 业务失败码：服务端已处理请求，但业务规则拒绝了它（HTTP 仍为 200） */
    public static final int BUSINESS_ERROR_CODE = 300;

    /** 参数错误码：请求参数缺失/格式错误/校验不通过（HTTP 仍为 200） */
    public static final int PARAM_ERROR_CODE = 320;

    /** 未登录码：未携带凭据或凭据已失效（HTTP 仍为 200） */
    public static final int UNAUTHORIZED_CODE = 401;

    /** 无权限码：已登录但权限/身份不足（HTTP 仍为 200） */
    public static final int FORBIDDEN_CODE = 403;

    /** 限流码：请求过于频繁，稍后重试（HTTP 仍为 200） */
    public static final int TOO_MANY_REQUESTS_CODE = 429;

    /** 默认成功响应消息 */
    public static final String SUCCESS_MSG = "success";

    /** 默认失败响应消息 */
    public static final String ERROR_MSG = "error";
}
