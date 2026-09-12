package com.astral.qt.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.QtMarkReadDto;
import com.astral.qt.entity.QtAppNotice;
import com.astral.qt.service.QtAppNoticeService;

import cn.dev33.satoken.stp.StpUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 轻听用户端公告控制器（App，需登录）
 * <p>消息中心、未读红点、已读回写。</p>
 * <p><b>已废弃</b>：统一通知迁移至 astral-plugin-feedback（sys_notice），
 * App 端已切换至 /api/v1/app/message/**；此接口保留兼容，不再被 App 调用。</p>
 * @deprecated 使用反馈插件的统一通知接口（/api/v1/app/message/**）
 */
@Deprecated
@Slf4j
@Tag(name = "轻听API-用户公告")
@RestController
@RequestMapping("/api/v1/user/notice")
public class QtUserNoticeController {

    @Resource
    private QtAppNoticeService noticeService;

    @Operation(summary = "消息中心列表（仅含下发到「消息中心」的公告，附已读状态）")
    @GetMapping("/center")
    public QtRestResp<List<QtAppNotice>> center(@RequestHeader(value = "satoken", required = false) String satoken) {
        return QtRestResp.success(noticeService.listMessageCenter(currentUserId()));
    }

    @Operation(summary = "消息中心未读数")
    @GetMapping("/unread/count")
    public QtRestResp<Long> unreadCount(@RequestHeader(value = "satoken", required = false) String satoken) {
        return QtRestResp.success(noticeService.countUnread(currentUserId()));
    }

    @Operation(summary = "标记已读（批量，幂等）")
    @PostMapping("/read")
    public QtRestResp<Void> read(@RequestHeader(value = "satoken", required = false) String satoken,
                                 @RequestBody QtMarkReadDto dto) {
        Long userId = currentUserId();
        List<Long> ids = dto.getIds();
        if (ids != null && !ids.isEmpty()) {
            noticeService.markRead(userId, ids);
        }
        return QtRestResp.success();
    }

    private Long currentUserId() {
        Object uid = null;
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                uid = attrs.getRequest().getAttribute("userId");
            }
        } catch (Exception ignored) {
        }
        if (uid == null) {
            Object loginId = StpUtil.getLoginIdDefaultNull();
            if (loginId != null) {
                uid = loginId;
            }
        }
        if (uid == null) {
            throw new com.astral.qt.common.QtException(401, "登录状态已失效");
        }
        return Long.parseLong(uid.toString());
    }
}
