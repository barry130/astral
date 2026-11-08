package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 歌单封面上传计划请求（直传/两段式代传统一入口）。
 *
 * <p>字段语义与 {@link QtUploadPlanReqDto} 一致，另带歌单平台标识（先做业务归属校验）。</p>
 */
@Data
public class QtCoverPlanReqDto {

    @NotBlank(message = "platform不能为空")
    @Size(max = 16, message = "platform长度不能超过16")
    private String platform;

    @NotBlank(message = "fileName不能为空")
    @Size(max = 255, message = "fileName长度不能超过255")
    private String fileName;

    @NotBlank(message = "contentType不能为空")
    @Size(max = 128, message = "contentType长度不能超过128")
    private String contentType;

    /** 文件大小（字节），服务端按文件夹策略收紧校验 */
    private long sizeBytes;

    @NotBlank(message = "idempotencyKey不能为空")
    @Size(max = 128, message = "idempotencyKey长度不能超过128")
    @Pattern(regexp = "[\\x21-\\x7E]+", message = "idempotencyKey仅支持可见ASCII字符")
    private String idempotencyKey;
}
