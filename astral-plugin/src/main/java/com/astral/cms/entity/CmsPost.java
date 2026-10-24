package com.astral.cms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * CMS 文章
 * <p>Markdown 正文，状态机：draft（草稿）/ published（已发布）/ private（私密，仅登录后可见）/ hidden（隐藏）。</p>
 */
@Data
@TableName("cms_post")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsPost {

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "draft";
    /** 状态：已发布（匿名可见） */
    public static final String STATUS_PUBLISHED = "published";
    /** 状态：私密（仅登录的管理员在博客前台可见，匿名访客完全不可见） */
    public static final String STATUS_PRIVATE = "private";
    /** 状态：隐藏（保留 URL 但前台不展示） */
    public static final String STATUS_HIDDEN = "hidden";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 标题 */
    private String title;

    /** URL 别名（可空，空则前台用 id 访问） */
    private String slug;

    /** 摘要（空则前台截取正文开头） */
    private String summary;

    /** Markdown 正文 */
    private String content;

    /** 封面图 URL */
    private String cover;

    /** 分类ID（cms_category.id，可空） */
    private Long categoryId;

    /** 状态：draft|published|hidden */
    private String status;

    /** 是否置顶 */
    private Boolean isTop;

    /** 浏览量 */
    private Long views;

    /** 作者（宿主 sys_user.id） */
    private Long authorId;

    /** 发布时间（首次发布自动写入） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime publishedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 软删除时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime deleteTime;

    /** 分类名称（主分类，服务端填充，非DB字段） */
    @TableField(exist = false)
    private String categoryName;

    /** 全部所属分类（第一项为主分类，服务端填充，非DB字段） */
    @TableField(exist = false)
    private List<CategoryRef> categories;

    /** 标签列表（服务端填充，非DB字段） */
    @TableField(exist = false)
    private List<CmsTag> tags;

    /**
     * 分类引用（仅 id/名称/别名，避免带出整棵分类树）
     */
    @Data
    public static class CategoryRef {
        /** 分类ID */
        private Long id;
        /** 分类名称 */
        private String name;
        /** URL 别名（可空） */
        private String slug;

        public CategoryRef() {
        }

        public CategoryRef(Long id, String name, String slug) {
            this.id = id;
            this.name = name;
            this.slug = slug;
        }
    }
}
