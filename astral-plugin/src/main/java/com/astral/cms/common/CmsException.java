package com.astral.cms.common;

/**
 * 内容管理插件业务异常
 * <p>携带业务码，由 CmsGlobalExceptionHandler 转成 Result。默认码 300（V2 契约：业务失败），
 * 需要更细语义时用 {@link #CmsException(Integer, String)} 显式指定（如 401 未登录、404 资源不存在）。</p>
 */
public class CmsException extends RuntimeException {

    private final Integer code;

    public CmsException(String message) {
        super(message);
        this.code = 300;
    }

    public CmsException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
