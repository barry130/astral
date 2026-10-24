package com.astral.cms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 页面（Wiki）保存（新增/修改）请求
 */
@Data
public class PageSaveDto {

    /** 页面标题 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 256, message = "标题最长 256 字")
    private String title;

    /** URL 别名（可空；留空前台用 id 访问） */
    @Size(max = 128, message = "URL 别名最长 128 字")
    private String slug;

    /** Markdown 正文 */
    @NotBlank(message = "正文不能为空")
    private String content;

    /** 父页面ID（可空=根） */
    private Long parentId;

    /** 排序（越小越靠前，默认 0） */
    private Integer sort;

    /** 状态：draft|published|hidden */
    @NotBlank(message = "状态不能为空")
    private String status;

    /** 发布时间（可空；首次发布未填时自动写入当前时间） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;
}
