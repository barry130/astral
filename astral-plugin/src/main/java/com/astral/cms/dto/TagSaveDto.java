package com.astral.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 标签保存（新增/修改）请求
 */
@Data
public class TagSaveDto {

    /** 标签名（唯一） */
    @NotBlank(message = "标签名不能为空")
    @Size(max = 64, message = "标签名最长 64 字")
    private String name;

    /** URL 别名（可空） */
    @Size(max = 128, message = "URL 别名最长 128 字")
    private String slug;
}
