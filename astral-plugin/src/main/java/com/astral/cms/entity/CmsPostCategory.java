package com.astral.cms.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CMS 文章×分类 关联（一篇文章可属于多个分类）
 */
@Data
@TableName("cms_post_category")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsPostCategory {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文章ID（cms_post.id） */
    private Long postId;

    /** 分类ID（cms_category.id） */
    private Long categoryId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
