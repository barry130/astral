package com.astral.cms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分类保存（新增/修改）请求
 */
@Data
public class CategorySaveDto {

    /** 分类名称 */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 64, message = "分类名称最长 64 字")
    private String name;

    /** URL 别名（可空） */
    @Size(max = 128, message = "URL 别名最长 128 字")
    private String slug;

    /** 父分类ID（可空=根） */
    private Long parentId;

    /** 排序（越小越靠前，默认 0） */
    private Integer sort;

    /** 描述（可空） */
    @Size(max = 256, message = "描述最长 256 字")
    private String description;
}
