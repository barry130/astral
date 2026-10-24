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
 * CMS 页面（Wiki，支持父子层级目录）
 * <p>与文章的区别：有 parent_id 层级、无分类/标签，用于文档/知识库。</p>
 * <p>状态机：draft（草稿）/ published（已发布）/ private（私密，仅登录后可见）/ hidden（隐藏）。</p>
 */
@Data
@TableName("cms_page")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsPage {

    /** 状态：草稿 */
    public static final String STATUS_DRAFT = "draft";
    /** 状态：已发布（匿名可见） */
    public static final String STATUS_PUBLISHED = "published";
    /** 状态：私密（仅登录的管理员在博客前台可见，匿名访客完全不可见） */
    public static final String STATUS_PRIVATE = "private";
    /** 状态：隐藏 */
    public static final String STATUS_HIDDEN = "hidden";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 页面标题 */
    private String title;

    /** URL 别名（可空，空则前台用 id 访问） */
    private String slug;

    /** Markdown 正文 */
    private String content;

    /** 父页面ID（NULL=根） */
    private Long parentId;

    /** 排序（越小越靠前） */
    private Integer sort;

    /** 状态：draft|published|hidden */
    private String status;

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

    /** 子页面（服务端组树填充，非DB字段） */
    @TableField(exist = false)
    private List<CmsPage> children;

    /** 面包屑（根→当前页，服务端填充，非DB字段） */
    @TableField(exist = false)
    private List<Crumb> breadcrumb;

    /**
     * 面包屑节点
     */
    @Data
    public static class Crumb {
        /** 页面ID */
        private Long id;
        /** 页面标题 */
        private String title;
        /** URL 别名（可空） */
        private String slug;
    }
}
