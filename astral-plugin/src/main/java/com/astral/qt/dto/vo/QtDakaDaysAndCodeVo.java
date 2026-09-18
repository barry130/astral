package com.astral.qt.dto.vo;

import lombok.Data;

/** 连续签到天数与总有效积分 */
@Data
public class QtDakaDaysAndCodeVo {

    /** 连续签到天数 */
    private Integer days;

    /** 有效总积分 */
    private Integer code;

    /** 今天是否已签到 */
    private Boolean todayDone;
}