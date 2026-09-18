package com.astral.qt.dto;

import lombok.Data;

/**
 * 音源包装载结果上报请求体（POST /api/v1/app/source/report，免认证）
 * <p>用于装机分布统计与坏包发现：某个版本在多少设备上装上了、是否出现冒烟自检失败。</p>
 */
@Data
public class QtSourceReportDto {

    /** 客户端平台（1101/1102/1103） */
    private Long platform;

    /** 客户端应用版本号 */
    private Long appVersionCode;

    /** 装载的音源包版本号 */
    private Long sourceVersionCode;

    /** 结果：ok / smoke_failed */
    private String result;

    /** 失败详情（可空） */
    private String detail;
}
