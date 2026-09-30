package com.astral.feedback.entity;

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
 * 统一通知表（sys_notice）
 * <p>轻听公告（qt_app_notice）超集 + 反馈/需求通知统一入口。</p>
 * <ul>
 *   <li>channel：逗号分隔的平台集合，取值与 {@code stat_platform} 同源
 *       （app-android / app-ios / app-windows / web），另可用 all 表示不限平台</li>
 *   <li>notice_type：announce 公告 | feedback 反馈 | request 需求</li>
 *   <li>user_id：NULL=广播；有值=点对点</li>
 *   <li>display：位掩码 1=开屏 2=通告栏 4=消息中心</li>
 * </ul>
 * <p>渠道的解析/匹配/归一统一走 {@link com.astral.feedback.common.NoticeChannel}，
 * 本类只保留常量别名，不要在业务代码里手写渠道字符串比较。</p>
 */
@Data
@TableName("sys_notice")
@JsonIgnoreProperties(ignoreUnknown = true)
public class SysNotice {

    /** 渠道：不限平台（所有平台可见），见 {@link com.astral.feedback.common.NoticeChannel#ALL} */
    public static final String CHANNEL_ALL = com.astral.feedback.common.NoticeChannel.ALL;
    /** 渠道：Android App（= stat_platform 的 app-android） */
    public static final String CHANNEL_ANDROID = com.astral.common.util.ClientHeaders.UT_ANDROID;
    /** 渠道：iOS App（= stat_platform 的 app-ios） */
    public static final String CHANNEL_IOS = com.astral.common.util.ClientHeaders.UT_IOS;
    /** 渠道：Windows 桌面端（= stat_platform 的 app-windows，旧值 pc） */
    public static final String CHANNEL_WINDOWS = com.astral.common.util.ClientHeaders.UT_WINDOWS;
    /** 渠道：Web / H5（= stat_platform 的 web） */
    public static final String CHANNEL_WEB = com.astral.common.util.ClientHeaders.UT_WEB;

    /**
     * 移动端双平台（Android + iOS）。
     * <p>改造前 {@code channel=app} 的等价物：qt-uniappx 同一份包同时跑在两端。</p>
     */
    public static final String CHANNEL_MOBILE = CHANNEL_ANDROID + "," + CHANNEL_IOS;

    /** @deprecated 遗留值 app，新代码请用 {@link #CHANNEL_MOBILE}；仅存量数据/老客户端参数还会出现 */
    @Deprecated
    public static final String CHANNEL_APP = "app";
    /** @deprecated 遗留值 pc，新代码请用 {@link #CHANNEL_WINDOWS}；仅存量数据/老客户端参数还会出现 */
    @Deprecated
    public static final String CHANNEL_PC = "pc";

    /** 类型：公告 */
    public static final String TYPE_ANNOUNCE = "announce";
    /** 类型：反馈 */
    public static final String TYPE_FEEDBACK = "feedback";
    /** 类型：需求 */
    public static final String TYPE_REQUEST = "request";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 渠道：逗号分隔的平台集合（或 all），取值与 stat_platform 同源；见 {@code NoticeChannel} */
    private String channel;

    /** 类型：announce 公告 | feedback 反馈 | request 需求 */
    private String noticeType;

    /** 点对点目标用户ID（NULL=广播） */
    private Long userId;

    /** 关联反馈ID */
    private Long feedbackId;

    /** 展示位掩码：1=开屏 2=通告栏 4=消息中心，可叠加 */
    private Long display;

    /** 标题 */
    private String title;

    /** 内容（可承载富文本） */
    private String content;

    /** 点击后跳转链接 */
    private String url;

    /** 是否启用 0-隐藏 1-展示 */
    private Long isShow;

    /** 是否置顶（置顶优先） */
    private Long isTop;

    /** 开屏弹窗是否可关闭 */
    private Long dialogClosable;

    /** 仅首次登录弹出 */
    private Long firstLoginOnly;

    /** 通告栏是否跑马灯 */
    private Long marquee;

    /** 生效时间；为空表示不限制开始 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveStart;

    /** 失效时间；为空表示不限制结束 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime effectiveEnd;

    /** 生效版本码下限（如 300 对应 3.0.0）；空不限 */
    private Long versionMin;

    /** 生效版本码上限；空不限 */
    private Long versionMax;

    /** 可见人群：ALL | LOGGED_IN | NOT_LOGGED_IN */
    private String audience;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 消息中心是否已读（前端缓存判断，后端不落库；此字段仅作扩展预留） */
    @TableField(exist = false)
    private Boolean read;
}
