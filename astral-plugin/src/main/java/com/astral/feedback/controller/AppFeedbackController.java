package com.astral.feedback.controller;

import com.astral.feedback.common.FeedbackRestResp;
import com.astral.feedback.dto.ReplyDto;
import com.astral.feedback.dto.SubmitFeedbackDto;
import com.astral.feedback.entity.Feedback;
import com.astral.feedback.entity.FeedbackReply;
import com.astral.feedback.service.FeedbackService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
 * 反馈插件 App 端反馈控制器
 * <p>挂载 /api/v1/app/feedback/**，由 FeedbackAuthInterceptor 鉴权（需登录）。</p>
 */
@Slf4j
@Tag(name = "反馈插件-用户反馈")
@RestController
@RequestMapping("/api/v1/app/feedback")
public class AppFeedbackController {

    @Resource
    private FeedbackService feedbackService;

    @Operation(summary = "提交反馈")
    @PostMapping("/submit")
    public FeedbackRestResp<Feedback> submit(@Valid @RequestBody SubmitFeedbackDto dto,
                                             @RequestHeader(value = "X-Device", required = false) String device,
                                             @RequestHeader(value = "X-OS", required = false) String os,
                                             @RequestHeader(value = "X-App-Version", required = false) String appVersion,
                                             @RequestHeader(value = "X-Platform", required = false) String platform,
                                             HttpServletRequest request) {
        Long userId = currentUserId();
        String ip = clientIp(request);
        return FeedbackRestResp.success(feedbackService.submit(userId, dto, device, os, appVersion, platform, ip));
    }

    @Operation(summary = "我的反馈（分页，全部状态）")
    @GetMapping("/my")
    public FeedbackRestResp<Page<Feedback>> my(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        return FeedbackRestResp.success(feedbackService.myPage(currentUserId(), pageNum, pageSize));
    }

    @Operation(summary = "公开列表（published AND is_public）")
    @GetMapping("/public")
    public FeedbackRestResp<Page<Feedback>> publicList(@RequestParam(defaultValue = "1") Integer pageNum,
                                                       @RequestParam(defaultValue = "10") Integer pageSize) {
        return FeedbackRestResp.success(feedbackService.publicPage(pageNum, pageSize));
    }

    @Operation(summary = "详情（本人 或 published+public）")
    @GetMapping("/{id}")
    public FeedbackRestResp<Feedback> detail(@PathVariable Long id) {
        return FeedbackRestResp.success(feedbackService.detail(currentUserId(), id, false));
    }

    @Operation(summary = "回复列表（升序，带昵称/用户类型）")
    @GetMapping("/{id}/replies")
    public FeedbackRestResp<List<FeedbackReply>> replies(@PathVariable Long id) {
        return FeedbackRestResp.success(feedbackService.replies(currentUserId(), id, false));
    }

    @Operation(summary = "用户回复（同时通知管理员）")
    @PostMapping("/reply")
    public FeedbackRestResp<FeedbackReply> reply(@Valid @RequestBody ReplyDto dto) {
        return FeedbackRestResp.success(feedbackService.userReply(currentUserId(), dto));
    }

    /** 当前用户ID：优先从拦截器注入的 request attribute 读取 */
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
            throw new com.astral.feedback.common.FeedbackException(401, "登录状态已失效");
        }
        return Long.parseLong(uid.toString());
    }

    /** 客户端IP：X-Forwarded-For 优先，回退 remoteAddr */
    private String clientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
