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
 * CMS 文章×标签 关联
 */
@Data
@TableName("cms_post_tag")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsPostTag {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 文章ID（cms_post.id） */
    private Long postId;

    /** 标签ID（cms_tag.id） */
    private Long tagId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
