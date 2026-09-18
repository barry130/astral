package com.astral.monitor.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 统计批量上报请求体
 * <p>
 * POST /api/v1/stat/report 的入参。单批 events 数量 ≤ 200。
 * </p>
 */
@Data
public class StatReportRequest {

    /** 事件列表（≤200 条） */
    @Valid
    @NotEmpty
    @Size(max = 200, message = "单批事件数量不能超过200")
    private List<StatEventDTO> events;
}
