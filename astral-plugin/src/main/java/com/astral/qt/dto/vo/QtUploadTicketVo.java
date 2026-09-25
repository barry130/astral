package com.astral.qt.dto.vo;

import com.astral.storage.security.UploadTicketService;

/**
 * 直传凭证视图（UPDATE_DESIGN.md §5.2/§5.3）
 * <p>uploadUrl/method/formField/formPolicy/formAuthorization 交给客户端向存储端直传；
 * uploadId 用于 complete 阶段回执。Astral 服务器不经手文件正文。</p>
 */
public record QtUploadTicketVo(String uploadUrl, String method, String formField, String uploadId,
                               long expiresAt, String formPolicy, String formAuthorization) {

    public static QtUploadTicketVo of(UploadTicketService.IssuedTicket t) {
        return new QtUploadTicketVo(t.uploadUrl(), t.method(), t.formField(), t.uploadId(),
                t.expiresAtEpochSeconds(),
                t.formFields() == null ? null : t.formFields().policy(),
                t.formFields() == null ? null : t.formFields().authorization());
    }
}
