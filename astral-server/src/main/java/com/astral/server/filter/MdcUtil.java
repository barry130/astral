package com.astral.server.filter;

import org.slf4j.MDC;

/**
 * MDC（Mapped Diagnostic Context）工具类
 * <p>用于在日志上下文中设置和获取Request ID，实现请求级别的日志追踪</p>
 * <p>MDC是SLF4J提供的线程级别上下文，同一线程中的所有日志都可以访问其中的数据</p>
 */
public final class MdcUtil {

    /** MDC中存储Request ID的键名 */
    private static final String KEY_REQUEST_ID = "requestId";

    /** 私有构造方法，防止实例化 */
    private MdcUtil() {}

    /**
     * 设置Request ID到MDC上下文
     *
     * @param requestId 请求唯一标识
     */
    public static void setRequestId(String requestId) {
        MDC.put(KEY_REQUEST_ID, requestId);
    }

    /**
     * 从MDC上下文获取Request ID
     *
     * @return 请求唯一标识
     */
    public static String getRequestId() {
        return MDC.get(KEY_REQUEST_ID);
    }

    /**
     * 清理MDC上下文
     * <p>请求结束后必须调用，防止线程池复用导致MDC数据污染</p>
     */
    public static void clear() {
        MDC.remove(KEY_REQUEST_ID);
    }
}
