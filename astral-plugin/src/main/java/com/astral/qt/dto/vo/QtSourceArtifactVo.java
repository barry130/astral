package com.astral.qt.dto.vo;

import lombok.Data;

/**
 * 音源包产物条目（artifacts 数组元素）
 * <p>artifacts 是「当前生效的全集」而不是「本次改动的差异集」：客户端按 path 逐个比 version，
 * 只下载 version 变了的那一个文件。</p>
 */
@Data
public class QtSourceArtifactVo {

    /** 文件路径，如 chain.json / source-bundle.js */
    private String path;

    /** 单文件版本号：客户端只按它判断某个文件要不要重下 */
    private Long version;

    /** 下载地址（OSS 永久地址） */
    private String url;
}
