package com.astral.system.notice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.astral.common.util.ClientHeaders;
import com.astral.dao.entity.SysNotice;
import com.astral.system.notice.NoticeService;
import com.astral.system.notice.NoticeTargets;
import com.astral.system.notice.dto.ReadAckDto;
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

import java.util.List;

/**
 * 通知管理 - App 端控制器。
 * <p>挂载 {@code /api/v1/app/message/**}（路径与原反馈插件版一字不变，已发布客户端直接可用）。
 * active 公开（游客可见）；center/unread-count/read-ack 需登录，由
 * {@link com.astral.system.notice.AppNoticeAuthInterceptor} 拦截。</p>
 * <p>登录子集另由 {@code @RequiresPermission} 做接口权限校验（App 端权限码前缀 {@code user:}）；
 * active 是公开接口，故意不加注解（类级注解会波及公开接口，故一律用方法级）。</p>
 *
 * <p><b>平台识别（老客户端兼容）</b>：三个接口都按「{@code X-App-Ut} 头 &gt; 遗留
 * {@code X-Platform} 头 &gt; {@code channel} 查询参数 &gt; 缺省 App」的顺序解析投放平台，
 * 详见 {@link NoticeTargets#resolveTargets}。已发布的客户端把 {@code channel=pc} 这类
 * 旧「端」值写死在二进制里，必须继续认；同时新客户端只发统一平台头即可，无需再带 channel。</p>
 *
 * <p>返回体是全仓统一的 {@link Result}（{@code {code,errorCode?,msg,data,timestamp}}），与宿主管理端同一个类；
 * 差异只在传输层——App 端恒 HTTP 200，成败由 body 的 {@code code} 表达（App 端解析逻辑按 {@code code} 判定）。</p>
 */
@Slf4j
@Tag(name = "通知管理-App端")
@RestController
@RequestMapping("/api/v1/app/message")
public class AppNoticeController {

    @Resource
    private NoticeService noticeService;

    @Operation(summary = "当前生效通知（公开，三展示位共用，按 display 位分发；平台取 X-App-Ut，"
            + "兼容遗留 channel=app|pc|web|all，缺省 app）")
    @GetMapping("/active")
    public Result<List<SysNotice>> active(@RequestParam(value = "versionCode", required = false) String versionCode,
                                          @RequestParam(value = "channel", required = false) String channel,
                                          @RequestHeader(value = ClientHeaders.H_UT, required = false) String utHeader,
                                          @RequestHeader(value = ClientHeaders.H_PLATFORM_LEGACY, required = false) String legacyPlatform,
                                          @RequestHeader(value = "satoken", required = false) String satoken) {
        Long userId = tryCurrentUserId();
        List<String> targets = NoticeTargets.resolveTargets(utHeader, legacyPlatform, channel);
        return Result.success(noticeService.listForChannel(targets, versionCode, userId != null, userId));
    }

    @Operation(summary = "消息中心（公告+通知，带 noticeType 标签；已读由前端缓存判断；"
            + "平台取 X-App-Ut，兼容遗留 channel=app|pc|web|all，缺省 app）")
    @RequiresPermission(value = "user:message:view", name = "消息查看", description = "App 端查看消息中心与未读数")
    @GetMapping("/center")
    public Result<List<SysNotice>> center(@RequestParam(value = "channel", required = false) String channel,
                                          @RequestHeader(value = ClientHeaders.H_UT, required = false) String utHeader,
                                          @RequestHeader(value = ClientHeaders.H_PLATFORM_LEGACY, required = false) String legacyPlatform) {
        List<String> targets = NoticeTargets.resolveTargets(utHeader, legacyPlatform, channel);
        return Result.success(noticeService.listMessageCenter(currentUserId(), targets));
    }

    @Operation(summary = "未读数（候选总数，已读判定在前端；平台取 X-App-Ut，兼容遗留 channel=app|pc|web|all，缺省 app）")
    @RequiresPermission(value = "user:message:view", name = "消息查看", description = "App 端查看消息中心与未读数")
    @GetMapping("/unread-count")
    public Result<Long> unreadCount(@RequestParam(value = "channel", required = false) String channel,
                                    @RequestHeader(value = ClientHeaders.H_UT, required = false) String utHeader,
                                    @RequestHeader(value = ClientHeaders.H_PLATFORM_LEGACY, required = false) String legacyPlatform) {
        List<String> targets = NoticeTargets.resolveTargets(utHeader, legacyPlatform, channel);
        return Result.success(noticeService.countUnread(currentUserId(), targets));
    }

    @Operation(summary = "已读回执（本期仅记日志）")
    @RequiresPermission(value = "user:message:read", name = "消息已读", description = "App 端提交消息已读回执")
    @PostMapping("/read-ack")
    public Result<Void> readAck(@RequestBody ReadAckDto dto) {
        noticeService.readAck(currentUserId(), dto.getIds() == null ? List.of() : dto.getIds());
        return Result.success();
    }

    /** 尝试获取当前用户 ID（未登录返回 null，用于公开接口） */
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

    /**
     * 当前用户 ID（未登录抛错）。
     * <p>理论不可达：{@code /api/v1/app/message/**} 的登录子集（center/unread-count/read-ack）
     * 已由拦截器先行校验并写入 {@code userId} 属性，未登录会先被拦截器以
     * {@code {code:401}} 拒绝，不会进到控制器。</p>
     */
    private Long currentUserId() {
        Long uid = tryCurrentUserId();
        if (uid == null) {
            throw new IllegalStateException("未登录");
        }
        return uid;
    }
}
