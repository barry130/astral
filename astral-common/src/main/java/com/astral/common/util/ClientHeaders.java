package com.astral.common.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 统一客户端系统头契约（反馈 + 统计共用一套）。
 *
 * <p>凡是请求 astral 后端的客户端（qt-uniappx / qt-pc / astral-front / 将来的小程序），
 * 都必须携带下面 4 个请求头；服务端两条独立链路——<b>接口统计采集</b>
 * （{@code ApiRequestMetricInterceptor} → {@code stat_api_hourly}）与
 * <b>反馈提交</b>（{@code AppFeedbackController.submit} → {@code sys_feedback}）——
 * 都从这里取值，保证同一份客户端信息在两处口径一致。</p>
 *
 * <table border="1">
 *   <caption>头 → 取值 → 落库列</caption>
 *   <tr><th>头名</th><th>含义</th><th>取值</th><th>上限</th><th>统计落库</th><th>反馈落库</th></tr>
 *   <tr><td>{@code X-App-Ut}</td><td>客户端平台</td>
 *       <td>{@code app-android} / {@code app-ios} / {@code app-windows} / {@code web}</td>
 *       <td>16</td><td>{@code stat_api_hourly.ut}</td><td>{@code sys_feedback.platform}</td></tr>
 *   <tr><td>{@code X-App-Version}</td><td>客户端版本号</td><td>语义化版本，如 {@code 1.1.0}</td>
 *       <td>32</td><td>{@code stat_api_hourly.app_version}</td><td>{@code sys_feedback.app_version}</td></tr>
 *   <tr><td>{@code X-Device}</td><td>设备型号 / 主机名</td><td>{@code Pixel 6} / {@code iPhone 15 Pro} / {@code DESKTOP-ABC}</td>
 *       <td>128</td><td>—</td><td>{@code sys_feedback.device}</td></tr>
 *   <tr><td>{@code X-OS}</td><td>操作系统及版本</td><td>{@code Android 14} / {@code iOS 18.2} / {@code Windows 11 Pro 23H2}</td>
 *       <td>64</td><td>—</td><td>{@code sys_feedback.os}</td></tr>
 * </table>
 *
 * <p><b>为什么 {@code ut} 要做白名单</b>：这四个头都是<b>客户端可任意伪造</b>的。
 * 统计侧是「服务端测量 + 客户端自报维度」，{@code ut} / {@code app_version} 直接参与
 * 分组与唯一键（{@code uk_stat_api_hourly}）。若不校验，一个爬虫每次换一个
 * {@code X-App-Ut} 就能把 {@code stat_api_hourly} 的分组数撑爆。所以未知值一律归
 * 空串（等价于「未携带」），报表侧的「全部平台」口径不受影响；将来新增平台，
 * 在这里加一个 {@link #UT_VALUES} 条目 + {@code stat_platform} 字典加一行即可。</p>
 *
 * <p>本类不依赖 Servlet API（astral-common 保持零依赖，同 {@link ClientIp}），
 * 由调用方自行取 header 字符串传入。</p>
 */
public final class ClientHeaders {

    // ==================== 头名 ====================

    /** 客户端平台（统一值取自 {@code stat_platform} 字典，见 {@link #UT_VALUES}） */
    public static final String H_UT = "X-App-Ut";

    /** 客户端版本号（与反馈侧同名同值，两处复用） */
    public static final String H_VERSION = "X-App-Version";

    /** 设备型号（移动端取机型，桌面端取主机名，Web 端取浏览器标识） */
    public static final String H_DEVICE = "X-Device";

    /** 操作系统及版本 */
    public static final String H_OS = "X-OS";

    /**
     * 遗留平台头：仅由旧版 App（qt-uniappx {@code feedback.ts} 早期实现）在提交反馈时发送，
     * 取值是裸 {@code android} / {@code ios}。客户端已不再发送，服务端保留读取做过渡兼容。
     */
    public static final String H_PLATFORM_LEGACY = "X-Platform";

    // ==================== 长度上限（与落库列宽一致，宁截断不失败） ====================

    /** 与 {@code stat_api_hourly.ut} / {@code sys_feedback.platform} 列宽一致 */
    public static final int MAX_UT = 16;

    /** 与 {@code stat_api_hourly.app_version} / {@code sys_feedback.app_version} 列宽一致 */
    public static final int MAX_VERSION = 32;

    /** 与 {@code sys_feedback.device} 列宽一致 */
    public static final int MAX_DEVICE = 128;

    /** 与 {@code sys_feedback.os} 列宽一致 */
    public static final int MAX_OS = 64;

    // ==================== 平台枚举（与 stat_platform 字典同源） ====================

    /** Android App */
    public static final String UT_ANDROID = "app-android";
    /** iOS App */
    public static final String UT_IOS = "app-ios";
    /** Windows 桌面端（qt-pc） */
    public static final String UT_WINDOWS = "app-windows";
    /** Web / H5（管理台 astral-front、轻听 Web 版） */
    public static final String UT_WEB = "web";

    /** 允许上报的平台集合；不在此列的值一律归空串 */
    public static final Set<String> UT_VALUES = Set.of(UT_ANDROID, UT_IOS, UT_WINDOWS, UT_WEB);

    /** 遗留 {@code X-Platform} 值 → 统一 {@code X-App-Ut} 值的映射（含已是新值的情况，做幂等） */
    private static final Map<String, String> LEGACY_PLATFORM_MAP = Map.of(
            "android", UT_ANDROID,
            "ios", UT_IOS,
            "windows", UT_WINDOWS,
            UT_ANDROID, UT_ANDROID,
            UT_IOS, UT_IOS,
            UT_WINDOWS, UT_WINDOWS,
            UT_WEB, UT_WEB);

    private ClientHeaders() {
    }

    // ==================== 归一 ====================

    /**
     * 通用归一：去空白 → 超长截断 → 空白/缺失归空串。
     *
     * @param raw       请求头原始值，可为 null
     * @param maxLength 目标列宽，超出部分截断
     * @return 可直接落库的字符串，永不返回 null
     */
    public static String normalize(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /**
     * 归一平台标识：先 {@link #normalize} 再走白名单，未知值归空串。
     *
     * @param raw 请求头原始值，可为 null
     * @return 合法的平台标识，或空串表示「未知/未携带」
     */
    public static String normalizeUt(String raw) {
        String value = normalize(raw, MAX_UT).toLowerCase(Locale.ROOT);
        return UT_VALUES.contains(value) ? value : "";
    }

    /**
     * 解析平台标识，新头优先、遗留头兜底。
     *
     * @param utRaw                {@code X-App-Ut} 原始值，可为 null
     * @param legacyPlatformRaw    {@code X-Platform} 原始值，可为 null（旧版 App）
     * @return 合法的平台标识，或空串
     */
    public static String resolveUt(String utRaw, String legacyPlatformRaw) {
        String value = normalizeUt(utRaw);
        if (!value.isEmpty()) {
            return value;
        }
        String legacy = normalize(legacyPlatformRaw, MAX_UT).toLowerCase(Locale.ROOT);
        if (legacy.isEmpty()) {
            return "";
        }
        return LEGACY_PLATFORM_MAP.getOrDefault(legacy, "");
    }

    /**
     * 判断是否为本服务端已知的平台标识（供报表侧校验筛选参数，避免拿脏值去查库）。
     *
     * @param value 待判断值
     * @return 命中白名单返回 true
     */
    public static boolean isKnownUt(String value) {
        return value != null && UT_VALUES.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
