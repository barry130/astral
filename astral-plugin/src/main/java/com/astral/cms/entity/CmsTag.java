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

/**
 * CMS 标签
 */
@Data
@TableName("cms_tag")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsTag {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 标签名（唯一） */
    private String name;

    /** URL 别名（可空） */
    private String slug;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 关联文章数（已发布，服务端填充，非DB字段） */
    @TableField(exist = false)
    private Long postCount;
}
