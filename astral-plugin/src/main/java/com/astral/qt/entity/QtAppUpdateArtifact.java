package com.astral.qt.entity;

import com.astral.qt.dto.vo.QtAppUpdateArtifactVo;
import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本更新产物（UPDATE_ARTIFACT_DESIGN）
 * <p>一个版本（qt_app_update.type + version_code）可以对应多个安装包：Windows 三架构
 * （x64 / x86 / arm64）、macOS x86_64 / aarch64、Linux deb 与 AppImage 等。版本本身
 * （版本号、更新说明、是否强更）留在主表，下载相关的字段下沉到本表按「平台 + 架构」分行。</p>
 * <p><b>匹配语义</b>：platform / arch 都允许为空 = 不限。命中优先级依次为
 * 「platform 精确匹配 + arch 精确匹配」&gt;「platform 精确匹配 + arch 为空（该平台无架构区分）」
 * &gt;「platform 为空 + arch 精确匹配」&gt;「platform 为空 + arch 为空（通用兜底）」，
 * 同一档位内按 sort 升序取第一条。全部不匹配时不改写主表取值（旧数据仍有主表直链）。</p>
 * <p>主表 download_url / browser_url / md5 / file_size 仍然保留，作为「旧客户端 +
 * 未上送 arch」的兜底值；后台保存产物列表时会自动把兜底值同步回主表。</p>
 */
@Data
@TableName("qt_app_update_artifact")
public class QtAppUpdateArtifact {

    @TableId(type = IdType.INPUT)
    private Long id;

    /** 所属版本 ID（qt_app_update.id） */
    private Long updateId;

    /** 平台：1101 安卓 / 1102 iOS / 1103 Windows / 1104 Linux / 1105 macOS / 1106 鸿蒙；空 = 不限平台 */
    private Long platform;

    /** CPU 架构：x64 / x86 / arm64 等（大小写不敏感，比对时归一化）；空 = 不限架构 */
    private String arch;

    /** 直链下载地址（GitHub 时填原始 release 链接） */
    private String downloadUrl;

    /** 浏览器下载地址（可空，桌面端兜底） */
    private String browserUrl;

    /** 直链是否为 GitHub 链接：1=是（参与加速拼接） 0=否 */
    private Long isGithub;

    /** 安装包大小（字节），可选 */
    private Long fileSize;

    /** 安装包 MD5，可选 */
    private String md5;

    /** 排序（小的在前，用于同一档位内取第一条） */
    private Long sort;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 对外 VO：只暴露 App 端需要的字段，不带主键与时间戳 */
    public QtAppUpdateArtifactVo toVo() {
        QtAppUpdateArtifactVo vo = new QtAppUpdateArtifactVo();
        vo.setPlatform(getPlatform());
        vo.setArch(getArch());
        vo.setDownloadUrl(getDownloadUrl());
        vo.setBrowserUrl(getBrowserUrl());
        vo.setIsGithub(getIsGithub());
        vo.setFileSize(getFileSize());
        vo.setMd5(getMd5());
        return vo;
    }
}
