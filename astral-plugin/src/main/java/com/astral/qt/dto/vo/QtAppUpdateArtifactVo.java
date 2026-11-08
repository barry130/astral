package com.astral.qt.dto.vo;

import lombok.Data;

/**
 * 版本更新产物条目（App 端 /api/v1/app/update 响应的 artifacts 列表元素）
 * <p>App 端默认直接用后端按 platform + arch 挑好的 downloadUrl / md5；
 * artifacts 全量清单用于「本机没被挑中时自行兜底」与后台排查。</p>
 */
@Data
public class QtAppUpdateArtifactVo {

    /** 平台：1101 安卓 / 1102 iOS / 1103 Windows / 1104 Linux / 1105 macOS / 1106 鸿蒙；null = 不限 */
    private Long platform;

    /** CPU 架构：x64 / x86 / arm64 等；null / 空 = 不限 */
    private String arch;

    /** 直链下载地址 */
    private String downloadUrl;

    /** 浏览器下载地址（可空） */
    private String browserUrl;

    /** 直链是否为 GitHub 链接：1=是（参与加速拼接） 0=否 */
    private Long isGithub;

    /** 安装包大小（字节），可选 */
    private Long fileSize;

    /** 安装包 MD5，可选 */
    private String md5;
}
