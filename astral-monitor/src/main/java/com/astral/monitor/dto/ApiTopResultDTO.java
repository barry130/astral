package com.astral.monitor.dto;

import lombok.Data;

import java.util.List;

/**
 * 接口调用 Top 榜结果（含当天全量汇总）
 * <p>
 * {@code list} 为调用次数前 N 的接口明细；{@code summary} 为当天<b>全部</b>接口的汇总
 * （总调用/成功/失败次数），与 Top N 的 limit 无关，避免"Top 10 / Top 20 总调用次数不一致"。
 * </p>
 */
@Data
public class ApiTopResultDTO {
    /** Top N 接口明细 */
    private List<ApiTopDTO> list;
    /** 当天全部接口汇总（limit 无关） */
    private ApiTopSummaryDTO summary;

    public ApiTopResultDTO() {
    }

    public ApiTopResultDTO(List<ApiTopDTO> list, ApiTopSummaryDTO summary) {
        this.list = list;
        this.summary = summary;
    }
}
