package com.astral.feedback.controller;

import com.astral.feedback.common.FeedbackRestResp;
import com.astral.feedback.dto.ReadAckDto;
import com.astral.feedback.entity.SysNotice;
import com.astral.feedback.service.FeedbackNoticeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.dev33.satoken.stp.StpUtil;

import java.util.List;

/**
 * 反馈插件 App 端通知控制器
 * <p>挂载 /api/v1/app/message/**。active 公开（游客可见）；center/unread-count/read-ack 需登录。</p>
 */
@Slf4j
@Tag(name = "反馈插件-用户通知")
@RestController
@RequestMapping("/api/v1/app/message")
public class AppMessageController {

    @Resource
    private FeedbackNoticeService noticeService;

    @Operation(summary = "当前生效通知（公开，三展示位共用，按 display 位分发；channel=app|pc|web，默认app）")
    @GetMapping("/active")
    public FeedbackRestResp<List<SysNotice>> active(@RequestParam(value = "versionCode", required = false) String versionCode,
                                                    @RequestParam(value = "channel", required = false, defaultValue = SysNotice.CHANNEL_APP) String channel,
                                                    @RequestHeader(value = "satoken", required = false) String satoken) {
        Long userId = tryCurrentUserId();
        return FeedbackRestResp.success(noticeService.listForChannel(channel, versionCode, userId != null, userId));
    }

    @Operation(summary = "消息中心（公告+反馈/需求通知，带 noticeType 标签；已读由前端缓存判断；channel=app|pc|web，默认app）")
    @GetMapping("/center")
    public FeedbackRestResp<List<SysNotice>> center(@RequestParam(value = "channel", required = false, defaultValue = SysNotice.CHANNEL_APP) String channel) {
        return FeedbackRestResp.success(noticeService.listMessageCenter(currentUserId(), channel));
    }

    @Operation(summary = "未读数（候选总数，已读判定在前端；channel=app|pc|web，默认app）")
    @GetMapping("/unread-count")
    public FeedbackRestResp<Long> unreadCount(@RequestParam(value = "channel", required = false, defaultValue = SysNotice.CHANNEL_APP) String channel) {
        return FeedbackRestResp.success(noticeService.countUnread(currentUserId(), channel));
    }

    @Operation(summary = "已读回执（本期仅记日志）")
    @PostMapping("/read-ack")
    public FeedbackRestResp<Void> readAck(@RequestBody ReadAckDto dto) {
        noticeService.readAck(currentUserId(), dto.getIds() == null ? List.of() : dto.getIds());
        return FeedbackRestResp.success();
    }

    /** 尝试获取当前用户ID（未登录返回 null，用于公开接口） */
    private Long tryCurrentUserId() {
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
        return uid == null ? null : Long.parseLong(uid.toString());
    }

    /** 当前用户ID（未登录抛 401） */
    private Long currentUserId() {
        Long uid = tryCurrentUserId();
        if (uid == null) {
            throw new com.astral.feedback.common.FeedbackException(401, "登录状态已失效");
        }
        return uid;
    }
}
