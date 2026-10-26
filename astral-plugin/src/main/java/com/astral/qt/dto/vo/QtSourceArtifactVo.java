package com.astral.qt.dto.vo;

import lombok.Data;

/**
 * 音源包产物条目（artifacts 数组元素）
 * <p>artifacts 是「当前生效的全集」而不是「本次改动的差异集」：客户端按 path 逐个比 version，
 * 只下载 version 变了的那一个文件。</p>
 * <p>packId / packVersionCode / packVersionName 是<b>服务端维护字段</b>：由
 * {@link com.astral.qt.service.QtPackHeaderProbe} 从产物首行 {@code /*__QT_PACK__*&#47;} 包头
 * 探测回填（建单/编辑后异步补齐，发布前同步补齐），客户端（qt-pc / qt-uniappx）优先拿它们
 * 直接比版、免 Range 探测；字段缺失时客户端回退到老逻辑（自己探产物头）。管理端上送的
 * 这三个字段一律忽略，mergeArtifacts 只在 url 未变时从旧值继承。</p>
 */
@Data
public class QtSourceArtifactVo {

    /** 文件路径，如 chain.json / source-bundle.js */
    private String path;

    /** 单文件版本号：客户端只按它判断某个文件要不要重下 */
    private Long version;

    /** 下载地址（OSS 永久地址） */
    private String url;

    /** 包头自述的包 id（如 meta-official / play-official）；服务端探测回填，非管理端输入 */
    private String packId;

    /** 包头自述的包版本号（yyyyMMddNN，与 release.sourceVersionCode 同口径）；服务端探测回填 */
    private Long packVersionCode;

    /** 包头自述的版本名；服务端探测回填，缺省时客户端用 release.sourceVersionName */
    private String packVersionName;
}
