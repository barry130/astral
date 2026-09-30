package com.astral.feedback.controller;

import org.springframework.beans.factory.annotation.Value;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.util.ClientHeaders;
import com.astral.common.util.ClientIp;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import cn.dev33.satoken.stp.StpUtil;

import java.util.List;

/**
 * 反馈插件 App 端反馈控制器
 * <p>挂载 /api/v1/app/feedback/**，由 FeedbackAuthInterceptor 鉴权（需登录），
 * 并由 {@code @RequiresPermission} 做接口权限校验（App 端权限码前缀 {@code user:}）。</p>
 * <p>
 * 客户端信息（平台/版本/设备/系统）统一从<b>客户端系统头</b>读取，
 * 契约与接口统计共用一套，见 {@link ClientHeaders}；未携带时按 null 落库，
 * 不阻塞提交。
 * </p>
 */
@Slf4j
@Tag(name = "反馈插件-用户反馈")
@RestController
@RequestMapping("/api/v1/app/feedback")
@RequiresPermission(value = "user:feedback:view", name = "反馈查看", description = "App 端查看我的反馈/公开反馈/反馈详情与回复列表")
public class AppFeedbackController {

    /** 可信代理列表（决定能否采信 X-Forwarded-For），与全站口径一致 */
    @Value("${astral.web.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;

    /** 遗留平台头名：旧版 App 只发这个头，保留兜底兼容 */
    @Value("${astral.stat.client-legacy-platform-header:" + ClientHeaders.H_PLATFORM_LEGACY + "}")
    private String legacyPlatformHeader;

    @Resource
    private FeedbackService feedbackService;

    @Operation(summary = "提交反馈")
    @RequiresPermission(value = "user:feedback:submit", name = "反馈提交", description = "App 端提交反馈与追加回复")
    @PostMapping("/submit")
    public FeedbackRestResp<Feedback> submit(@Valid @RequestBody SubmitFeedbackDto dto,
                                             HttpServletRequest request) {
        Long userId = currentUserId();
        String ip = clientIp(request);
        // 统一系统头：X-App-Ut（平台，回退遗留 X-Platform）/ X-App-Version（版本）/
        // X-Device（设备型号·主机名）/ X-OS（操作系统）。空值与超长在 ClientHeaders 内归一。
        String platform = ClientHeaders.resolveUt(
                request.getHeader(ClientHeaders.H_UT), request.getHeader(legacyPlatformHeader));
        String appVersion = ClientHeaders.normalize(
                request.getHeader(ClientHeaders.H_VERSION), ClientHeaders.MAX_VERSION);
        String device = ClientHeaders.normalize(
                request.getHeader(ClientHeaders.H_DEVICE), ClientHeaders.MAX_DEVICE);
        String os = ClientHeaders.normalize(
                request.getHeader(ClientHeaders.H_OS), ClientHeaders.MAX_OS);
        return FeedbackRestResp.success(
                feedbackService.submit(userId, dto, blankToNull(device), blankToNull(os),
                        blankToNull(appVersion), blankToNull(platform), ip));
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
    @RequiresPermission(value = "user:feedback:submit", name = "反馈提交", description = "App 端提交反馈与追加回复")
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

    /** 空白归一为 null：sys_feedback 的这几个列允许 NULL，空串会污染「未上报」的判定 */
    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * 客户端IP：与全站口径一致走 {@link ClientIp}（可信代理白名单 + 从右往左取第一个非代理地址）。
     * 早期实现直接采信 {@code X-Forwarded-For} 的首位，该头客户端可任意伪造。
     */
    private String clientIp(HttpServletRequest request) {
        return ClientIp.resolve(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies);
    }
}
