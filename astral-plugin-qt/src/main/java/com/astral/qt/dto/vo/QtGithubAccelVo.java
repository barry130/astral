package com.astral.qt.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * App 端 GitHub 加速节点（UPDATE_DESIGN.md §2.1②）
 * <p>仅暴露探测所需最小字段：id/name/prefixUrl。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QtGithubAccelVo {

    private Long id;

    private String name;

    /** 加速前缀（最终地址 = prefixUrl + 原始链接） */
    private String prefixUrl;
}
