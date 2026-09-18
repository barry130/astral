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
 *   <li>channel：app | pc | web | all</li>
 *   <li>notice_type：announce 公告 | feedback 反馈 | request 需求</li>
 *   <li>user_id：NULL=广播；有值=点对点</li>
 *   <li>display：位掩码 1=开屏 2=通告栏 4=消息中心</li>
 * </ul>
 */
@Data
@TableName("sys_notice")
@JsonIgnoreProperties(ignoreUnknown = true)
public class SysNotice {

    /** 渠道：App */
    public static final String CHANNEL_APP = "app";
    /** 渠道：PC（桌面端） */
    public static final String CHANNEL_PC = "pc";
    /** 渠道：Web */
    public static final String CHANNEL_WEB = "web";
    /** 渠道：全部（所有端可见） */
    public static final String CHANNEL_ALL = "all";

    /** 类型：公告 */
    public static final String TYPE_ANNOUNCE = "announce";
    /** 类型：反馈 */
    public static final String TYPE_FEEDBACK = "feedback";
    /** 类型：需求 */
    public static final String TYPE_REQUEST = "request";

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /** 渠道：app | pc | web | all */
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
