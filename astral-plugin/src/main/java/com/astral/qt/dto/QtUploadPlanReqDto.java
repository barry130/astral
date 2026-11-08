package com.astral.qt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 头像上传计划请求（直传/两段式代传统一入口）。
 *
 * <p>idempotencyKey 由客户端生成（重试复用同一键）：目录首选渠道为 CUSTOM_HTTP 时，
 * 服务端用它创建两段式上传意图（幂等重放返回同一操作）；直传家族渠道下仅声明元数据、不参与签发。</p>
 */
@Data
public class QtUploadPlanReqDto {

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
