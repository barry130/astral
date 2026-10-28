package com.astral.system.notice;

/**
 * 通知展示位掩码（{@code sys_notice.display}）。
 *
 * <p>display 是位掩码列：1 = 开屏、2 = 通告栏、4 = 消息中心，可同时命中多位。
 * 原先这三组常量连同 {@code hasDisplay} 分别写在反馈插件的通知服务里与
 * {@code notify.InappServiceImpl} 的私有常量里（同值两份），通知能力收归宿主后
 * 收口到本类，读写两侧共用一份定义。</p>
 */
public final class NoticeDisplay {

    /** 展示位掩码：开屏 */
    public static final long DISPLAY_SPLASH = 1L;

    /** 展示位掩码：通告栏 */
    public static final long DISPLAY_NOTICE_BAR = 2L;

    /** 展示位掩码：消息中心 */
    public static final long DISPLAY_MESSAGE_CENTER = 4L;

    private NoticeDisplay() {
    }

    /** display 是否命中指定位（display 为空视为不命中） */
    public static boolean hasDisplay(Long display, long bit) {
        return display != null && (display & bit) == bit;
    }
}
