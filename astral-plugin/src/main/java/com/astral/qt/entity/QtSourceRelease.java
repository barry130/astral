package com.astral.qt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 音源包发布记录（SOURCE_UPDATE_DESIGN）
 * <p>
 * 一个音源包可同时服务多个平台（{@link #platforms}），因此 platform 不参与唯一性；
 * 唯一键是 (source_version_code, channel)。
 * </p>
 * <p>
 * <b>版本号与版本名由后端生成</b>：客户端与发布脚本一律不上送，
 * 规则见 {@code QtSourceService#nextVersionCode}。
 * </p>
 */
@Data
@TableName("qt_source_release")
public class QtSourceRelease {

    /** 平台：安卓 */
    public static final Long PLATFORM_ANDROID = 1101L;
    /** 平台：iOS */
    public static final Long PLATFORM_IOS = 1102L;
    /** 平台：Windows 桌面端 */
    public static final Long PLATFORM_WINDOWS = 1103L;

    /** 全部受支持的平台（与 qt_app_update 同源） */
    private static final List<Long> SUPPORTED_PLATFORMS =
            Arrays.asList(PLATFORM_ANDROID, PLATFORM_IOS, PLATFORM_WINDOWS);

    /** 渠道：正式版 */
    public static final String CHANNEL_STABLE = "stable";
    /** 渠道：测试版 */
    public static final String CHANNEL_BETA = "beta";

    /**
     * 判断平台是否受支持
     *
     * @param platform 平台（1101/1102/1103）
     * @return 受支持返回 true
     */
    public static boolean isSupportedPlatform(Long platform) {
        return platform != null && SUPPORTED_PLATFORMS.contains(platform);
    }

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 音源包版本号（yyyyMMddNN，后端生成，更新判定的唯一依据） */
    private Long sourceVersionCode;

    /** 音源包版本名（yyyy.MM.dd.N，后端由版本号派生，仅展示） */
    private String sourceVersionName;

    /** 适用平台（逗号分隔，如 "1101,1103"） */
    private String platforms;

    /** 需要的宿主契约版本（高于客户端支持上限则不加载） */
    private Long hostApiVersion;

    /** 按平台准入的应用版本号（JSON，如 {"1103":[102],"1101":[304]}；平台缺省或空数组=不限制） */
    private String appVersionCodes;

    /** 发布渠道：stable 正式（所有用户可收到）/ beta 测试（仅 qt_admin/qt_tester 权限用户可收到） */
    private String channel;

    /** 更新说明（客户端设置页展示） */
    private String notes;

    /** 产物清单（JSON 数组，当前生效全集：[{path,version,url}]） */
    private String artifacts;

    /** 指定回退到的版本号（可空） */
    private Long rollbackTo;

    /** 是否标记坏包：1 是（客户端回退并拉黑该版本） */
    private Integer isBad;

    /** 是否发布：1 已发布（客户端可拉到） */
    private Integer isPublished;

    /** 发布时间（未发布为空；manifest 里作为 publishedAt 返回） */
    private LocalDateTime publishedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
