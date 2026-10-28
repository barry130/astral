package com.astral.system.message;

import lombok.Data;

/**
 * 趋势图单点：某一天 × 某一个投递渠道的投递量。
 *
 * <p>返回的是**稠密网格**（窗口内每天都出现、且每个渠道都有一行，无数据时为 0），
 * 前端直接按 {@code date} 分组即可画出折线，不需要自己补空档。</p>
 */
@Data
public class TrendPointVo {

    /** 日期（yyyy-MM-dd，服务端本地时区） */
    private String date;

    /** 投递渠道：EMAIL / SMS / INAPP */
    private String channel;

    private long total;

    private long success;

    /** INAPP 恒为 0 */
    private long fail;
}
