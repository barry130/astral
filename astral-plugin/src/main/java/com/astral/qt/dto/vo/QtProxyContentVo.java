package com.astral.qt.dto.vo;

import com.astral.storage.dto.UploadOperationDtos;

/**
 * 两段式代传正文回执（qt 域紧凑视图）。
 *
 * <p>{@code state} 语义与 storage 插件两段式状态机一致：{@code COMMITTED} 才算上传完成
 * （此时 {@code fileId} 非空，可调 apply）；{@code CREATED} 表示本次尝试的渠道失败、
 * 已切换到备用渠道，客户端应当重发同一正文；{@code FAILED}/{@code UNKNOWN_OUTCOME} 终态失败。</p>
 */
public record QtProxyContentVo(
        String state, boolean contentAccepted, String fileId, String safeCode, Integer httpStatus) {

    public static QtProxyContentVo of(UploadOperationDtos.UploadContentResp resp) {
        UploadOperationDtos.UploadOperationView operation = resp.operation();
        return new QtProxyContentVo(
                operation.state(), resp.contentAccepted(), operation.fileId(), resp.safeCode(), resp.httpStatus());
    }
}
