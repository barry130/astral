package com.astral.qt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量收藏请求体（LIKE_SYNC_DESIGN.md §2.5，POST /api/v1/app/user/like/batch）。
 * <p>歌曲/歌单操作混排、按数组顺序执行；单批上限 200 对齐统计上报先例
 * （StatReportRequest），超限由全局异常处理器返回 320。</p>
 */
@Data
public class QtLikeBatchDto {

    @NotEmpty(message = "ops不能为空")
    @Size(max = 200, message = "单批操作数量不能超过200")
    @Valid
    private List<QtLikeBatchOpDto> ops;
}
