package com.astral.feedback.common;

import com.astral.common.util.ClientHeaders;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通知渠道（sys_notice.channel）取值与匹配规则。
 *
 * <p><b>值域与统计平台同源</b>：平台部分直接复用 {@link ClientHeaders} 的常量
 * （{@code app-android} / {@code app-ios} / {@code app-windows} / {@code web}），
 * 也就是 {@code stat_platform} 字典的取值 —— 通知投放维度与接口统计/反馈的平台口径
 * 完全一致；额外的 {@value #ALL} 是本表独有的「不限平台」哨兵，<b>不得写进
 * {@code stat_platform} 字典</b>（那会让它在统计筛选里变成一个真实平台）。</p>
 *
 * <p><b>存储形态</b>：单列 {@code VARCHAR(64)} 存逗号分隔的平台集合，
 * 例：{@code app-android,app-ios}。管理员可多选；{@value #ALL} 与具体平台互斥。</p>
 *
 * <p><b>老客户端兼容</b>（本类存在的主要理由）：改造前渠道是
 * {@code app | pc | web | all} 这一套「端」值，已发布的 App/PC 客户端把
 * {@code channel=pc} 之类的值写死在二进制里，无法强制升级，因此服务端必须
 * 继续认识它们：</p>
 *
 * <table border="1">
 *   <caption>遗留渠道值 → 新平台集合</caption>
 *   <tr><th>请求来源</th><th>遗留值</th><th>展开为</th></tr>
 *   <tr><td>qt-uniappx（Android/iOS 同一份包）</td><td>{@code app}（也是缺省值）</td>
 *       <td>{@code app-android} + {@code app-ios}</td></tr>
 *   <tr><td>qt-pc（Tauri 桌面端）</td><td>{@code pc}</td><td>{@code app-windows}</td></tr>
 *   <tr><td>Web / H5</td><td>{@code web}</td><td>{@code web}（新老同值）</td></tr>
 *   <tr><td>任意端</td><td>{@code all}</td><td>{@code all}（不限平台）</td></tr>
 * </table>
 *
 * <p>取值优先级：{@code X-App-Ut} 请求头（白名单）&gt; 遗留 {@code X-Platform} 头
 * &gt; {@code channel} 查询参数。三者皆空或都无法识别时回落到
 * {@link #DEFAULT_TARGETS}（等价于旧的 {@code channel=app}），
 * <b>而不是「全部平台」</b> —— 未知值一旦放宽成全体，等于把定向公告泄露给所有人。</p>
 */
public final class NoticeChannel {

    /** 「不限平台」哨兵：命中即对所有平台可见 */
    public static final String ALL = "all";

    /** 平台取值（与 stat_platform 字典、X-App-Ut 同源）；顺序 = 字典 sort，输出稳定 */
    public static final List<String> PLATFORMS = List.of(
            ClientHeaders.UT_ANDROID,
            ClientHeaders.UT_IOS,
            ClientHeaders.UT_WINDOWS,
            ClientHeaders.UT_WEB);

    /** 遗留渠道值：App（Android + iOS 合并端） */
    public static final String LEGACY_APP = "app";
    /** 遗留渠道值：PC / 桌面端 */
    public static final String LEGACY_PC = "pc";

    /** 移动端双平台（遗留 {@code app} 展开后的集合，也是缺省投放集合） */
    private static final List<String> MOBILE_APP = List.of(ClientHeaders.UT_ANDROID, ClientHeaders.UT_IOS);

    /**
     * 缺省投放集合：客户端既没带平台头、也没带 {@code channel} 参数时使用。
     * 等价于改造前的 {@code channel=app} 缺省行为，保证老 APK 可见范围不缩水也不放大。
     */
    public static final List<String> DEFAULT_TARGETS = MOBILE_APP;

    /** 落库列宽（与 sys_notice.channel 一致），最长合法值 {@code app-android,app-ios,app-windows,web} = 35 */
    public static final int MAX_STORED_LENGTH = 64;

    private NoticeChannel() {
    }

    // ==================== 请求侧：解析出「要看哪些平台」 ====================

    /**
     * 解析当前请求应匹配的平台集合。
     *
     * @param utHeader           {@code X-App-Ut} 头，可为 null
     * @param legacyPlatformHead {@code X-Platform} 头（旧版 App 遗留），可为 null
     * @param channelParam       {@code channel} 查询参数（旧版客户端写死的端标识），可为 null
     * @return 待匹配平台集合；含 {@link #ALL} 表示不限平台
     */
    public static List<String> resolveTargets(String utHeader, String legacyPlatformHead, String channelParam) {
        // 1) 统一平台头（新客户端）——白名单校验，未知归空串
        String ut = ClientHeaders.resolveUt(utHeader, legacyPlatformHead);
        if (!ut.isEmpty()) {
            return List.of(ut);
        }
        // 2) 老客户端：没有平台头，只能靠它写死的 channel 参数
        return legacyTargets(channelParam);
    }

    /**
     * 遗留渠道值 → 平台集合。未知值 / 空值一律回落 {@link #DEFAULT_TARGETS}。
     *
     * @param legacy 遗留值，可含逗号（容错）
     */
    public static List<String> legacyTargets(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return DEFAULT_TARGETS;
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : legacy.split(",")) {
            String v = part.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                continue;
            }
            if (LEGACY_APP.equals(v)) {
                out.addAll(MOBILE_APP);
            } else if (LEGACY_PC.equals(v)) {
                out.add(ClientHeaders.UT_WINDOWS);
            } else if (PLATFORMS.contains(v) || ALL.equals(v)) {
                out.add(v);
            }
            // 其余未知值：忽略（不参与放宽）
        }
        return out.isEmpty() ? DEFAULT_TARGETS : List.copyOf(out);
    }

    // ==================== 存储侧：归一 + 匹配 ====================

    /**
     * 把落库值解析成平台集合，顺带兼容存量遗留值（{@code app} / {@code pc}）。
     *
     * @param stored 列值，可为 null / 空 / 逗号分隔
     */
    public static Set<String> parseStored(String stored) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (stored != null && !stored.isBlank()) {
            for (String part : stored.split(",")) {
                String v = part.trim().toLowerCase(Locale.ROOT);
                if (v.isEmpty()) {
                    continue;
                }
                if (PLATFORMS.contains(v) || ALL.equals(v)) {
                    out.add(v);
                } else if (LEGACY_APP.equals(v)) {
                    out.addAll(MOBILE_APP);
                } else if (LEGACY_PC.equals(v)) {
                    out.add(ClientHeaders.UT_WINDOWS);
                }
                // 无法识别的存量脏值：忽略，避免误投放
            }
        }
        // 空值/全脏值按「老数据未填渠道」处理，与旧 DEFAULT 'app' 语义一致
        return out.isEmpty() ? Set.copyOf(DEFAULT_TARGETS) : Set.copyOf(out);
    }

    /**
     * 该通知是否投放到给定平台集合中的任一平台。
     *
     * @param stored  通知的 channel 列值
     * @param targets 请求侧解析出的平台集合
     */
    public static boolean matches(String stored, Collection<String> targets) {
        if (targets == null || targets.isEmpty()) {
            return false;
        }
        Set<String> own = parseStored(stored);
        if (own.contains(ALL) || targets.contains(ALL)) {
            return true;
        }
        for (String t : targets) {
            if (own.contains(t)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 归一化待落库的渠道值：接受前端多选数组拼成的逗号串，也接受旧管理端提交的单值。
     * <p>{@link #ALL} 独占；空输入回落 {@link #DEFAULT_TARGETS}；输出按
     * {@link #PLATFORMS} 固定顺序，保证同一选择集合的存储形态唯一（便于筛选/去重）。</p>
     *
     * @param raw 原始值（逗号分隔，或单个遗留值），可为 null
     * @return 可落库的逗号分隔平台串
     */
    public static String normalizeForStore(String raw) {
        if (raw == null || raw.isBlank()) {
            return join(DEFAULT_TARGETS);
        }
        LinkedHashSet<String> picked = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String v = part.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                continue;
            }
            if (ALL.equals(v)) {
                return ALL;
            }
            if (PLATFORMS.contains(v)) {
                picked.add(v);
            } else if (LEGACY_APP.equals(v)) {
                picked.addAll(MOBILE_APP);
            } else if (LEGACY_PC.equals(v)) {
                picked.add(ClientHeaders.UT_WINDOWS);
            }
            // 其余未知值丢弃，不写入脏值
        }
        if (picked.isEmpty()) {
            return join(DEFAULT_TARGETS);
        }
        List<String> ordered = new ArrayList<>();
        for (String p : PLATFORMS) {
            if (picked.contains(p)) {
                ordered.add(p);
            }
        }
        return join(ordered);
    }

    /**
     * 管理端筛选条件解析：逗号分隔，逐项归一；无法识别的项直接丢弃
     * （筛选场景宁可筛不出，也不要因为未知值放宽成全表）。
     *
     * @param raw 原始筛选值，可为 null
     * @return 待筛选平台集合，空表示不筛
     */
    public static List<String> parseFilter(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String v = part.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) {
                continue;
            }
            if (PLATFORMS.contains(v) || ALL.equals(v)) {
                out.add(v);
            } else if (LEGACY_APP.equals(v)) {
                out.addAll(MOBILE_APP);
            } else if (LEGACY_PC.equals(v)) {
                out.add(ClientHeaders.UT_WINDOWS);
            }
        }
        return List.copyOf(out);
    }

    private static String join(Collection<String> values) {
        return String.join(",", values);
    }
}
