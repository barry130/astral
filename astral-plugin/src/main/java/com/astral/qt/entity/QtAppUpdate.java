package com.astral.qt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.astral.qt.dto.vo.QtAppUpdateArtifactVo;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * APP 版本更新信息
 * <p>
 * <b>一行多平台</b>（与公告 {@code sys_notice.channel} 同款方案）：{@link #platforms} 是逗号分隔的
 * 平台号集合（如 {@code "1101,1106"}），一行 = 一个版本，同一版本号发多个平台不再拆成多行。
 * 平台匹配一律在 Java 侧<b>精确拆分</b>比较（{@link #containsPlatform}），<b>不要用 SQL LIKE</b> ——
 * 平台号互为子串（如 1101 与 11011），LIKE 会误命中。
 * </p>
 * <p>旧结构是单值列 {@code type}（一行一个平台），已由 Flyway {@code V20261011001} 平移到
 * {@code platforms} 并删除该列。客户端契约不变：qt-pc / qt-uniappx 仍按单值 {@code type} 请求，
 * 服务端在 platforms 集合里做包含判断。</p>
 */
@Data
@TableName("qt_app_update")
public class QtAppUpdate {

    /** 平台：安卓 */
    public static final Long PLATFORM_ANDROID = 1101L;
    /** 平台：iOS */
    public static final Long PLATFORM_IOS = 1102L;
    /** 平台：Windows 桌面端 */
    public static final Long PLATFORM_WINDOWS = 1103L;
    /** 平台：Linux 桌面端 */
    public static final Long PLATFORM_LINUX = 1104L;
    /** 平台：macOS 桌面端 */
    public static final Long PLATFORM_MACOS = 1105L;
    /** 平台：HarmonyOS（qt-uniappx 鸿蒙原生包） */
    public static final Long PLATFORM_HARMONY = 1106L;

    /** 全部受支持的平台 */
    private static final List<Long> SUPPORTED_PLATFORMS =
            Arrays.asList(PLATFORM_ANDROID, PLATFORM_IOS, PLATFORM_WINDOWS, PLATFORM_LINUX, PLATFORM_MACOS,
                    PLATFORM_HARMONY);

    /** 平台列表存储长度上限（platforms VARCHAR(64)，六个平台全选也只用 29 字符） */
    public static final int PLATFORMS_MAX_LENGTH = 64;

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 版本号，如 222 对应 2.2.2 */
    private Long versionCode;

    /**
     * 适用平台（逗号分隔，如 "1101,1106"）：1101 安卓 / 1102 iOS / 1103 Windows /
     * 1104 Linux / 1105 macOS / 1106 鸿蒙。至少一个，匹配在 Java 侧精确拆分。
     */
    private String platforms;

    private String versionName;

    private String versionInfo;

    private String updateType;

    /** 直链下载地址（GitHub 时填原始 release 链接；历史 && 多链接已废弃） */
    private String downloadUrl;

    /** 发布渠道：stable 正式版 / beta 测试版 */
    private String channel;

    /** 下载方式（已废弃保留：新逻辑按双链接并存，不再读取） */
    private String downloadMode;

    /** 浏览器下载地址（可空，双链接之一） */
    private String browserUrl;

    /** 直链是否为 GitHub 链接：1=是（参与加速拼接） 0=否 */
    private Long isGithub;

    /** 是否强制更新：0 非强制 / 1 强制 */
    private Integer isForce;

    /** 是否发布：1 已发布(App端能收到更新通知) / 0 未发布(仅本地版本测试，不推送、不校验非官方) */
    private Integer isPublished;

    /** 安装包大小（字节），可选 */
    private Long fileSize;

    /** 安装包 MD5，可选 */
    private String md5;

    /**
     * 产物清单（非表字段，UPDATE_ARTIFACT_DESIGN）：该版本下的全部安装包明细。
     * <p>服务端已按客户端上送的 platform + arch 把命中的那一条写回上面的
     * downloadUrl / browserUrl / md5 / fileSize；artifacts 是完整备查清单，
     * 客户端在服务端挑不中（或自己知道该下哪个）时可自行挑选。</p>
     */
    @TableField(exist = false)
    private List<QtAppUpdateArtifactVo> artifacts;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    // ==================== platforms（逗号分隔多平台）工具 ====================

    /**
     * 判断单个平台是否受支持
     *
     * @param platform 平台号（1101/1102/1103/1104/1105/1106）
     * @return 受支持返回 true
     */
    public static boolean isSupportedPlatform(Long platform) {
        return platform != null && SUPPORTED_PLATFORMS.contains(platform);
    }

    /**
     * 解析 platforms CSV 为平台号列表（去空、去重、保持原顺序）。
     * <p>无法解析的片段直接忽略，不抛异常：库里的历史脏值不应让整个列表接口 500。</p>
     *
     * @param csv 逗号分隔平台号，如 "1101,1106"；可为 null
     * @return 平台号列表，永不为 null
     */
    public static List<Long> parsePlatforms(String csv) {
        List<Long> platforms = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return platforms;
        }
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                Long value = Long.valueOf(trimmed);
                if (!platforms.contains(value)) {
                    platforms.add(value);
                }
            } catch (NumberFormatException ignored) {
                // 脏数据片段跳过
            }
        }
        return platforms;
    }

    /**
     * 平台号列表 → platforms CSV（去重、保持原顺序）。
     *
     * @param platforms 平台号列表；可为 null
     * @return CSV 字符串（空列表返回 ""）
     */
    public static String toPlatformsCsv(List<Long> platforms) {
        if (platforms == null || platforms.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Long p : new LinkedHashSet<>(platforms)) {
            if (p == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /**
     * platforms CSV 是否包含指定平台（精确拆分比较，避免子串误命中）
     *
     * @param csv      逗号分隔平台号；null/空返回 false
     * @param platform 待查平台号
     * @return 包含返回 true
     */
    public static boolean containsPlatform(String csv, Long platform) {
        if (csv == null || csv.isBlank() || platform == null) {
            return false;
        }
        String target = String.valueOf(platform);
        for (String part : csv.split(",")) {
            if (target.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 实例方法：本行是否适用于指定平台（App 端 / 后台列表按平台筛选的统一入口）
     *
     * @param platform 客户端平台号
     * @return 适用返回 true
     */
    public boolean supportsPlatform(Long platform) {
        return containsPlatform(getPlatforms(), platform);
    }
}
