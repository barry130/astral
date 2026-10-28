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
 * channel 取值与 {@code stat_platform} / {@link ClientHeaders} 的平台标识同源；notice_type 只有
 * <b>公告 / 通知</b> 两类：{@code announce} = 广播（user_id 为空，开屏/通告栏/消息中心），
 * {@code notice} = 点对点站内信（user_id 非空）。历史值 {@code feedback} / {@code request}
 * 保留为只读别名（见 {@link #TYPE_LEGACY_FEEDBACK} / {@link #TYPE_LEGACY_REQUEST}），
 * 归并到「通知」一组，不做数据迁移 —— 已发布客户端把这两个值硬编码在二进制里，改不动。
 * 业务代码请使用本类常量，不要再手写渠道/类型字符串比较。
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

    /** Linux 客户端 */
    public static final String CHANNEL_LINUX = ClientHeaders.UT_LINUX;

    /** macOS 客户端 */
    public static final String CHANNEL_MACOS = ClientHeaders.UT_MACOS;

    /** HarmonyOS 客户端 */
    public static final String CHANNEL_HARMONY = ClientHeaders.UT_HARMONY;

    /** Web 端 */
    public static final String CHANNEL_WEB = ClientHeaders.UT_WEB;

    /** 移动端（Android + iOS） */
    public static final String CHANNEL_MOBILE = CHANNEL_ANDROID + "," + CHANNEL_IOS;

    /** 通知类型：公告（广播，user_id 为空；沿用历史存值，字典 label 也是「公告」） */
    public static final String TYPE_ANNOUNCE = "announce";

    /** 通知类型：通知（点对点站内信，user_id 非空；本次新增的值） */
    public static final String TYPE_NOTICE = "notice";

    /**
     * 历史类型值：反馈（= 通知的一种业务来源）。
     * <p><b>仅用于读侧归并</b>：存量 297 行点对点通知的 notice_type 仍是 {@code feedback} /
     * {@code request}，已发布客户端按这两个值渲染蓝/橙标签，因此不能改数据；
     * 服务端只把它们当作「通知」对待（筛选、展示归并），<b>新写入一律用 {@link #TYPE_NOTICE}</b>。</p>
     */
    @Deprecated
    public static final String TYPE_LEGACY_FEEDBACK = "feedback";

    /**
     * 历史类型值：需求（= 通知的一种业务来源）。
     * <p>语义同 {@link #TYPE_LEGACY_FEEDBACK}，仅读侧归并使用。</p>
     */
    @Deprecated
    public static final String TYPE_LEGACY_REQUEST = "request";

    /**
     * 客户端内跳转协议前缀（写在 {@code sys_notice.url} 上）。
     * <p>约定格式 {@code astral://<host>/<id>}，host 由两端客户端各自维护白名单映射到自己的路由：
     * {@code feedback} = 反馈详情。解析不出来的一律按「不可点」处理，服务端下发字符串不会变成
     * 可导航的任意路径。{@code url} 仍是 http(s) 时按外链打开，行为不变。</p>
     */
    public static final String LINK_SCHEME = "astral://";

    /** 反馈详情跳转：{@code astral://feedback/<feedbackId>} */
    public static final String LINK_FEEDBACK = LINK_SCHEME + "feedback/";

    private NoticeConstants() {
    }
}
