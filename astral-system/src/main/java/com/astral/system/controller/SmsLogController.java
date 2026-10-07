package com.astral.system.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysSmsLog;
import com.astral.system.notify.SysSmsLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 短信发送日志（sys_sms_log）：内容列存变量 JSON 透传留档，正文在供应商侧。
 */
@Tag(name = "短信发送日志")
@RestController
@RequestMapping("/api/v1/admin/system/sms/log")
@RequiredArgsConstructor
public class SmsLogController {

    private static final String PERM_VIEW = "admin:system:notify:sms:view";

    private final SysSmsLogService logService;

    @Operation(summary = "分页查询（可按手机号/插件/状态过滤）")
    @RequiresPermission(PERM_VIEW)
    @GetMapping("/page")
    public Result<Page<SysSmsLog>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                        @RequestParam(required = false) String phone,
                                        @RequestParam(required = false) String pluginId,
                                        @RequestParam(required = false) Integer status) {
        LambdaQueryWrapper<SysSmsLog> qw = new LambdaQueryWrapper<SysSmsLog>()
                .eq(phone != null && !phone.isBlank(), SysSmsLog::getPhone, phone)
                .eq(pluginId != null && !pluginId.isBlank(), SysSmsLog::getPluginId, pluginId)
                .eq(status != null, SysSmsLog::getStatus, status)
                .orderByDesc(SysSmsLog::getSendTime);
        return Result.success(logService.page(new Page<>(pageNum, pageSize), qw));
    }
}
