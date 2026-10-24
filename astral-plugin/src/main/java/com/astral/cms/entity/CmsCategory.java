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
 * CMS 分类（支持树形，parent_id 自引用）
 */
@Data
@TableName("cms_category")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsCategory {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 分类名称 */
    private String name;

    /** URL 别名（可空） */
    private String slug;

    /** 父分类ID（NULL=根） */
    private Long parentId;

    /** 排序（越小越靠前） */
    private Integer sort;

    /** 分类描述 */
    private String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 子分类（服务端组树填充，非DB字段） */
    @TableField(exist = false)
    private List<CmsCategory> children;

    /** 已发布文章数（服务端填充，非DB字段） */
    @TableField(exist = false)
    private Long postCount;
}
