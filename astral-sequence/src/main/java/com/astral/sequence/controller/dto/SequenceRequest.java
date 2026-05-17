package com.astral.sequence.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 序列号生成请求 DTO
 * <p>
 * 用于单个序列号生成请求的参数封装。
 * </p>
 */
@Data
public class SequenceRequest {

    /**
     * 业务键
     * <p>
     * 用于区分不同业务的序列号空间。每个业务键有独立的序列号序列。
     * 不能为空，最大长度为 64 个字符。
     * </p>
     */
    @NotBlank(message = "bizKey cannot be blank")
    @Size(max = 64, message = "bizKey must be at most 64 characters")
    private String bizKey;

    /**
     * 序列号生成类型（可选）
     * <p>
     * 指定使用哪种生成器：SNOWFLAKE、SEGMENT、REDIS、DATABASE、SIMPLE。
     * 如果为空，则使用配置文件中设置的默认类型。
     * </p>
     */
    private String type;
}
