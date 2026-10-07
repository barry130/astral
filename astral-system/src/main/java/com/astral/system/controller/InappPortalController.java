package com.astral.system.controller;

import com.astral.common.result.Result;
import com.astral.dao.entity.SysNotice;
import com.astral.system.notify.InappService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 站内信门户端（/all，登录即可）：自己的收件箱、未读数、已读。
 * 用户身份取 AuthInterceptor 注入的 userId（sys_user.id），不信任请求参数。
 */
@Tag(name = "站内信（门户）")
@RestController
@RequestMapping("/api/v1/all/notify/inapp")
@RequiredArgsConstructor
public class InappPortalController {

    private final InappService inappService;

    @Operation(summary = "我的收件箱（时间倒序）")
    @GetMapping("/my/page")
    public Result<Page<SysNotice>> myPage(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize,
                                               HttpServletRequest request) {
        return Result.success(inappService.myPage(currentUserId(request), pageNum, pageSize));
    }

    @Operation(summary = "未读数")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount(HttpServletRequest request) {
        return Result.success(inappService.unreadCount(currentUserId(request)));
    }

    @Operation(summary = "标记已读")
    @PostMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id, HttpServletRequest request) {
        inappService.markRead(currentUserId(request), id);
        return Result.success();
    }

    @Operation(summary = "全部已读")
    @PostMapping("/read-all")
    public Result<Void> readAll(HttpServletRequest request) {
        inappService.markAllRead(currentUserId(request));
        return Result.success();
    }

    private static Long currentUserId(HttpServletRequest request) {
        Object userId = request.getAttribute("userId");
        if (userId == null) {
            // 理论不可达：AuthInterceptor 对 /all/** 已保证登录态
            throw new IllegalStateException("未登录");
        }
        return Long.valueOf(userId.toString());
    }
}
