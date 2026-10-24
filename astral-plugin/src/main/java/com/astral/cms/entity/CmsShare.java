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
 * CMS 分享链接
 * <p>为单篇文章或单个页面生成带随机 token 的访问链接，可随时撤销、可设过期时间。
 * 分享链接的最大价值是让「私密」内容以只读方式分享给未登录访客，因此访问分享链接时
 * 不受内容可见性（status=private）限制。</p>
 */
@Data
@TableName("cms_share")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsShare {

    /** 目标类型：文章 */
    public static final String TARGET_POST = "post";
    /** 目标类型：页面 */
    public static final String TARGET_PAGE = "page";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 目标类型：post|page */
    private String targetType;

    /** 目标ID（cms_post.id 或 cms_page.id） */
    private Long targetId;

    /** 分享令牌（URL 安全随机串，唯一） */
    private String token;

    /** 过期时间（NULL=永久有效） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expireAt;

    /** 备注（用途说明，便于管理） */
    private String remark;

    /** 创建人（宿主 sys_user.id） */
    private Long createBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 目标标题（服务端填充，非DB字段） */
    @TableField(exist = false)
    private String targetTitle;

    /** 分享链接（服务端按站点配置拼装，非DB字段） */
    @TableField(exist = false)
    private String url;

    /** 是否已过期（服务端计算，非DB字段） */
    @TableField(exist = false)
    private Boolean expired;
}
