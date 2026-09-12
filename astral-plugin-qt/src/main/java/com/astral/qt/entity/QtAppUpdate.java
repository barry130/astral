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

/** APP 版本更新信息 */
@Data
@TableName("qt_app_update")
public class QtAppUpdate {

    /** 平台：安卓 */
    public static final Long TYPE_ANDROID = 1101L;
    /** 平台：iOS */
    public static final Long TYPE_IOS = 1102L;
    /** 平台：Windows 桌面端 */
    public static final Long TYPE_WINDOWS = 1103L;

    /** 全部受支持的平台 */
    private static final List<Long> SUPPORTED_TYPES = Arrays.asList(TYPE_ANDROID, TYPE_IOS, TYPE_WINDOWS);

    /**
     * 判断平台类型是否受支持
     *
     * @param type 平台类型（1101/1102/1103）
     * @return 受支持返回true
     */
    public static boolean isSupportedType(Long type) {
        return type != null && SUPPORTED_TYPES.contains(type);
    }

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 版本号，如 222 对应 2.2.2 */
    private Long versionCode;

    /** 客户端平台类型：1101 安卓 / 1102 iOS / 1103 Windows */
    private Long type;

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

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}