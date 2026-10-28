package com.astral.system.controller;

import java.util.List;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.system.dto.MessageDeliveryVo;
import com.astral.system.dto.MessageStatisticsVo;
import com.astral.system.message.MessageStatisticsQuery;
import com.astral.system.message.MessageStatisticsService;
import com.astral.system.message.TrendPointVo;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 消息统计（三渠道统一视图）。
 *
 * <p>把邮件日志、短信日志、站内信聚合成一个「消息」概念对外提供三个接口：
 * 概览数字、按天趋势、合并明细。渠道映射：{@code sys_mail_log} → EMAIL、
 * {@code sys_sms_log} → SMS、{@code sys_notice} → INAPP
 * （{@code sys_notice.channel} 存的是平台定向值 all/app-windows…，不是投递渠道）。</p>
 *
 * <p>三个接口共用 {@link MessageStatisticsQuery} 里的同一组筛选条件，参数名即字段名，
 * 因此概览、趋势、明细三者的口径永远一致。</p>
 */
@Tag(name = "消息统计")
@RestController
@RequestMapping("/api/v1/admin/system/message/statistics")
@RequiredArgsConstructor
public class MessageStatisticsController {

    /** 消息统计查看权限（与邮箱/短信日志统计同一权限码） */
    private static final String PERM_VIEW = "admin:system:mail:statistics:view";

    private final MessageStatisticsService messageStatisticsService;

    @Operation(summary = "三渠道统计概览（总量/成功率/今日/按渠道/按场景/按插件）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/overview")
    public Result<MessageStatisticsVo> overview(@ModelAttribute MessageStatisticsQuery query) {
        return Result.success(messageStatisticsService.overview(query));
    }

    @Operation(summary = "按天 × 渠道趋势（稠密网格，days 默认 7、上限 90）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/trend")
    public Result<List<TrendPointVo>> trend(@ModelAttribute MessageStatisticsQuery query) {
        return Result.success(messageStatisticsService.trend(query));
    }

    @Operation(summary = "合并分页明细（三渠道按发送时间倒序合并后切页）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/list")
    public Result<Page<MessageDeliveryVo>> list(@ModelAttribute MessageStatisticsQuery query) {
        return Result.success(messageStatisticsService.list(query));
    }
}
