package com.astral.qt.dto.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端手动探活结果（UPDATE_DESIGN.md §2.4）
 * <p>仅展示给管理员，不写库、不影响 App 端。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QtGithubAccelProbeVo {

    private Long id;

    private String name;

    private String prefixUrl;

    /** 是否可用（HTTP 200/206） */
    private Boolean alive;

    /** 探测耗时（毫秒） */
    private Long latencyMs;

    /** 结果说明：HTTP 状态码 / 失败原因（连接超时、连接失败、URL 拼接非法等） */
    private String message;
}
