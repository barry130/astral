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
 * CMS 评论（匿名提交 + 审核制）
 * <p>目标可为文章或页面（target_type + target_id）。
 * email/ip/userAgent 仅管理端可见，公开接口不返回。</p>
 */
@Data
@TableName("cms_comment")
@JsonIgnoreProperties(ignoreUnknown = true)
public class CmsComment {

    /** 目标类型：文章 */
    public static final String TARGET_POST = "post";
    /** 目标类型：页面 */
    public static final String TARGET_PAGE = "page";

    /** 状态：待审核 */
    public static final String STATUS_PENDING = "pending";
    /** 状态：已通过 */
    public static final String STATUS_APPROVED = "approved";
    /** 状态：已拒绝 */
    public static final String STATUS_REJECTED = "rejected";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 目标类型：post|page */
    private String targetType;

    /** 目标ID（cms_post.id 或 cms_page.id） */
    private Long targetId;

    /** 昵称（游客填写） */
    private String nickname;

    /** 邮箱（可选，仅管理端可见，用于联系） */
    private String email;

    /** 评论内容（纯文本） */
    private String content;

    /** 状态：pending|approved|rejected */
    private String status;

    /** 提交 IP（服务端取，仅管理端可见） */
    private String ip;

    /** User-Agent（服务端取，仅管理端可见） */
    private String userAgent;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 目标标题（服务端填充，非DB字段） */
    @TableField(exist = false)
    private String targetTitle;
}
