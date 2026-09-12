package com.astral.system.controller;

import com.astral.auth.security.PermissionChecker;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysMailLog;
import com.astral.system.mail.SysMailLogService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@Tag(name = "邮件统计")
@RestController
@RequestMapping("/api/v1/admin/system/mail/log")
@RequiredArgsConstructor
public class MailLogController {

    /** 邮箱统计查看权限（sys_permission: system:mail:statistics:view） */
    private static final String PERM_VIEW = "system:mail:statistics:view";

    private final SysMailLogService logService;
    private final PermissionChecker permissionChecker;

    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<SysMailLog>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize,
                                         @RequestParam(required = false) Long accountId,
                                         @RequestParam(required = false) String pluginId,
                                         @RequestParam(required = false) String toEmail,
                                         @RequestParam(required = false) Integer status,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime start,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime end) {
        permissionChecker.require(PERM_VIEW);
        return Result.success(logService.pageWithFilter(pageNum, pageSize, accountId, pluginId, toEmail, status, start, end));
    }

    @Operation(summary = "统计概览")
    @GetMapping("/statistics")
    public Result<Object> statistics() {
        permissionChecker.require(PERM_VIEW);
        return Result.success(logService.statistics());
    }
}
