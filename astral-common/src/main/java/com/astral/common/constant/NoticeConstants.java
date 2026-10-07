package com.astral.common.constant;

import com.astral.common.util.ClientHeaders;

/**
 * 统一通知表 sys_notice 的渠道 / 类型常量
 * <p>
 * 这些常量原本定义在 {@code com.astral.feedback.entity.SysNotice} 实体上，但该实体已按
 * 「宿主表 schema JSON 驱动 SchemaEntitySync 自动生成」的约定下沉到 astral-dao，生成器不会输出
 * {@code public static final} 成员，手写的常量会在每次启动同步时被覆盖删除，因此统一上提到
 * astral-common（宿主与插件共同依赖）保存。
 * </p>
 * <p>
 * channel 取值与 {@code stat_platform} / {@link ClientHeaders} 的平台标识同源；notice_type 区分
 * 公告 / 反馈 / 需求三类通知。业务代码请使用本类常量，不要再手写渠道字符串比较。
 * </p>
 */
public class NoticeConstants {

    /** 不限平台（广播） */
    public static final String CHANNEL_ALL = "all";

    /** Android 客户端 */
    public static final String CHANNEL_ANDROID = ClientHeaders.UT_ANDROID;

    /** iOS 客户端 */
    public static final String CHANNEL_IOS = ClientHeaders.UT_IOS;

    /** Windows 客户端 */
    public static final String CHANNEL_WINDOWS = ClientHeaders.UT_WINDOWS;

    /** Web 端 */
    public static final String CHANNEL_WEB = ClientHeaders.UT_WEB;

    /** 移动端（Android + iOS） */
    public static final String CHANNEL_MOBILE = CHANNEL_ANDROID + "," + CHANNEL_IOS;

    /** 通知类型：公告 */
    public static final String TYPE_ANNOUNCE = "announce";

    /** 通知类型：反馈 */
    public static final String TYPE_FEEDBACK = "feedback";

    /** 通知类型：需求 */
    public static final String TYPE_REQUEST = "request";

    private NoticeConstants() {
    }
}
