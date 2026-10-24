package com.astral.cms.common;

/**
 * 内容管理插件业务异常
 * <p>携带 code（默认 400），由 CmsGlobalExceptionHandler 转成 Result。</p>
 */
public class CmsException extends RuntimeException {

    private final Integer code;

    public CmsException(String message) {
        super(message);
        this.code = 400;
    }

    public CmsException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
