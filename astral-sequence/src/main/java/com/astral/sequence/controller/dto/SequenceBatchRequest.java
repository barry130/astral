package com.astral.sequence.controller.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 批量序列号生成请求 DTO
 * <p>
 * 用于批量序列号生成请求的参数封装。
 * 与 {@link SequenceRequest} 类似，但增加了数量参数。
 * </p>
 */
@Data
public class SequenceBatchRequest {

    /**
     * 业务键
     * <p>
     * 用于区分不同业务的序列号空间。
     * 不能为空，最大长度为 64 个字符。
     * </p>
     */
    @NotBlank(message = "bizKey cannot be blank")
    @Size(max = 64, message = "bizKey must be at most 64 characters")
    private String bizKey;

    /**
     * 序列号生成类型（可选）
     * <p>
     * 指定使用哪种生成器。如果为空，则使用默认类型。
     * </p>
     */
    private String type;

    /**
     * 批量生成的数量
     * <p>
     * 范围限制在 1-1000 之间，防止一次性生成过多序列号导致性能问题。
     * </p>
     */
    @Min(value = 1, message = "count must be at least 1")
    @Max(value = 1000, message = "count must be at most 1000")
    private Integer count;
}
