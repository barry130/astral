package com.astral.qt.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * 轻听 App 公告 / 公告展示配置
 * <p>
 * type 字段为「展示渠道位掩码」，按位表示一条公告需要下发到哪些展示位：
 * <ul>
 *   <li>bit1 (1)  = 开屏弹窗 SplashDialog</li>
 *   <li>bit2 (2)  = 首页顶部通告栏 NoticeBar</li>
 *   <li>bit3 (4)  = 消息中心列表 MessageCenter</li>
 * </ul>
 * 例如「开屏弹窗 + 消息中心存档」= 1 + 4 = 5。
 */
@Data
@TableName("qt_app_notice")
@JsonIgnoreProperties(ignoreUnknown = true)
public class QtAppNotice {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 展示渠道位掩码，见类注释 */
    private Long type;

    /** 点击后跳转链接 */
    private String url;

    /** 预留 uid（历史字段，一般留空） */
    private String uid;

    /** 公告标题 */
    private String title;

    /** 公告正文（可承载富文本） */
    private String content;

    /** 是否启用 0-隐藏 1-展示 */
    private Long isShow;

    /** 是否置顶（置顶公告优先于非置顶） */
    private Long isTop;

    /** 开屏弹窗是否可关闭（仅 type 含开屏弹窗时生效） */
    private Long dialogClosable;

    /** 仅首次登录弹出（仅 type 含开屏弹窗时生效） */
    private Long firstLoginOnly;

    /** 通告栏是否跑马灯滚动（仅 type 含通告栏时生效） */
    private Long marquee;

    /** 生效时间；为空表示不限制开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveStart;

    /** 失效时间；为空表示不限制结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveEnd;

    /** 生效 APP 版本号下限（版本号，如 300 对应 3.0.0）；为空不限制 */
    @TableField("version_min")
    private Long versionMin;

    /** 生效 APP 版本号上限（版本号，如 399）；为空不限制 */
    @TableField("version_max")
    private Long versionMax;

    /** 可见人群：ALL=全部, LOGGED_IN=仅登录用户, NOT_LOGGED_IN=仅游客(未登录) */
    private String audience;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 消息中心是否已读（仅消息中心接口返回，不落库） */
    @TableField(exist = false)
    private Boolean read;
}
