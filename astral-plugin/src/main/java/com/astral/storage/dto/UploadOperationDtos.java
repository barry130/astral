package com.astral.storage.dto;

/**
 * 两段式上传操作（§7.2–§7.4）的对外契约。
 *
 * <p>状态机：CREATED → RECEIVING → SENDING → REMOTE_SUCCEEDED → COMMITTED；
 * 异常分支 UNKNOWN_OUTCOME / FAILED / EXPIRED。HTTP 200 不等于业务成功，只有
 * {@code state=COMMITTED} 才算完成。</p>
 */
public final class UploadOperationDtos {

    private UploadOperationDtos() {}

    /**
     * 创建上传意图：只声明要传什么，不携带正文。
     *
     * @param folderId    目标目录
     * @param fileName    原始文件名（服务端会归一化后参与幂等指纹）
     * @param contentType 声明的 MIME，后续 PUT 必须完全一致
     * @param sizeBytes   声明的大小，必须等于 PUT 的 Content-Length
     * @param sha256      可选的内容 SHA-256（小写 hex，64 位）
     */
    public record UploadCreateReq(Long folderId, String fileName, String contentType, Long sizeBytes, String sha256) {}

    /** 创建响应：contentPath 为后续 PUT 的相对路径 */
    public record UploadCreateResp(
            String uploadId,
            String publicId,
            String state,
            boolean canSendContent,
            String contentPath,
            long maxBytes,
            long intentTtlSeconds) {}

    /**
     * 上传操作视图。
     *
     * @param canSendContent 当前是否允许 PUT 正文（仅 CREATED 且未过期）
     * @param fileId         仅在 COMMITTED 后非空
     * @param safeFailureCode 固定安全码，不含上游正文与凭据
     * @param intentExpiresAt 意图过期时间（epoch millis）
     * @param sendDeadline    发送截止时间（epoch millis）
     */
    public record UploadOperationView(
            String uploadId,
            String publicId,
            String state,
            boolean canSendContent,
            String contentPath,
            long maxBytes,
            Long folderId,
            String originalName,
            String contentType,
            Long expectedSize,
            String fileId,
            String safeFailureCode,
            long intentExpiresAt,
            long sendDeadline,
            String quotaCharge,
            String quotaDay) {}

    /**
     * PUT 正文结果。
     *
     * @param contentAccepted 正文是否被本次请求接受（重复 PUT 为 false，需按 operation 状态判断）
     * @param httpStatus      上游 HTTP 状态（未发送时为空）
     */
    public record UploadContentResp(
            boolean contentAccepted, UploadOperationView operation, Integer httpStatus, String safeCode) {}
}
