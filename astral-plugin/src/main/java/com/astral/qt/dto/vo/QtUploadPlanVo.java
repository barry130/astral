package com.astral.qt.dto.vo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 媒体上传计划（直传与两段式代传的统一入口响应，2026-11 §代传支持）。
 *
 * <p>{@code mode=DIRECT}：目录首选渠道是直传家族（TELEGRAM/S3 系/COS/OSS/又拍云），
 * {@code ticket} 为原有直传凭证，客户端按既有三段式直传，不经过 Astral 服务器。</p>
 * <p>{@code mode=PROXY}：目录首选渠道是 CUSTOM_HTTP（如 ImgBB），浏览器直传被安全策略
 * 禁止（STORAGE039——直传凭证会把上游凭据泄露给客户端），客户端改为两段式代传：
 * 原始字节 {@code POST contentPath}（PUT 兼容保留；Content-Type 与声明一致），响应
 * {@code state=COMMITTED} 后凭 {@code uploadId} 调 apply 接口落到业务字段。
 * {@code intentTtlSeconds} 为意图剩余存活秒数。
 * {@code contentPath} 是<strong>站点绝对路径</strong>（以 {@code /api/v1/} 开头）：客户端解析时只拼
 * 协议+域名（origin），不要再拼 API base 前缀，否则产生双前缀畸形路径被网关拦截。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QtUploadPlanVo(
        String mode,
        QtUploadTicketVo ticket,
        String uploadId,
        String contentPath,
        Long maxBytes,
        Long intentTtlSeconds) {

    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_PROXY = "PROXY";

    public static QtUploadPlanVo direct(QtUploadTicketVo ticket) {
        return new QtUploadPlanVo(MODE_DIRECT, ticket, null, null, null, null);
    }

    public static QtUploadPlanVo proxy(String uploadId, String contentPath, long maxBytes, long intentTtlSeconds) {
        return new QtUploadPlanVo(MODE_PROXY, null, uploadId, contentPath, maxBytes, intentTtlSeconds);
    }
}
