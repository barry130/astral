package com.astral.cms.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 文章保存（新增/修改）请求
 */
@Data
public class PostSaveDto {

    /** 标题 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 256, message = "标题最长 256 字")
    private String title;

    /** URL 别名（可空；留空前台用 id 访问） */
    @Size(max = 128, message = "URL 别名最长 128 字")
    private String slug;

    /** 摘要（可空） */
    @Size(max = 512, message = "摘要最长 512 字")
    private String summary;

    /** Markdown 正文 */
    @NotBlank(message = "正文不能为空")
    private String content;

    /** 封面图 URL（可空） */
    @Size(max = 512, message = "封面最长 512 字")
    private String cover;

    /** 分类ID（可空；旧的单分类字段，多分类时取 categoryIds 第一项冗余保存）
     * @deprecated 新代码请使用 {@link #categoryIds}，此处保留以兼容历史前端
     */
    @Deprecated
    private Long categoryId;

    /** 分类ID集合（可空；一篇文章可属于多个分类，第一个作为主分类） */
    private List<Long> categoryIds;

    /** 标签ID集合（可空；更新时以本次提交为准） */
    private List<Long> tagIds;

    /** 状态：draft|published|hidden */
    @NotBlank(message = "状态不能为空")
    private String status;

    /** 是否置顶 */
    private Boolean isTop;

    /** 发布时间（可空；首次发布未填时自动写入当前时间） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;
}
