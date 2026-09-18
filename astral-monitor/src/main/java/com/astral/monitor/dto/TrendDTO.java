package com.astral.monitor.dto;

import lombok.Data;

import java.util.List;

/**
 * 小时趋势数据传输对象（trend / api/trend）
 * <p>
 * 数组长度恒为 24，无数据小时补 0。
 * </p>
 */
@Data
public class TrendDTO {
    /** 小时标签（00:00 ~ 23:00） */
    private List<String> hours;
    /** 今日值（24 点，补零） */
    private List<Long> today;
    /** 昨日值（24 点，补零） */
    private List<Long> yesterday;

    /**
     * 构建小时标签序列（00:00 ~ 23:00）
     */
    public static List<String> hourLabels() {
        List<String> labels = new java.util.ArrayList<>(24);
        for (int h = 0; h < 24; h++) {
            labels.add(String.format("%02d:00", h));
        }
        return labels;
    }
}
