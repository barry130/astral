package com.astral.qt.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.service.UserLoginMarker;
import com.astral.common.annotation.RateLimit;
import com.astral.common.annotation.RequiresPermission;
import com.astral.dao.entity.User;
import com.astral.qt.common.QtException;
import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.QtAvatarTicketReqDto;
import com.astral.qt.dto.QtChangePwByEmailDto;
import com.astral.qt.dto.QtDeactivateDto;
import com.astral.qt.dto.QtCoverTicketReqDto;
import com.astral.qt.dto.QtLikeBatchDto;
import com.astral.qt.dto.QtLikePlaylistActionDto;
import com.astral.qt.dto.QtLikeSongActionDto;
import com.astral.qt.dto.QtLoginDto;
import com.astral.qt.dto.QtRegisterDto;
import com.astral.qt.dto.QtSendEmailDto;
import com.astral.qt.dto.QtUpdateUserDto;
import com.astral.qt.dto.QtUploadCompleteReqDto;
import com.astral.qt.dto.QtUserDakaDto;
import com.astral.qt.dto.vo.QtDakaDaysAndCodeVo;
import com.astral.qt.dto.vo.QtLikeChangesVo;
import com.astral.qt.dto.vo.QtLikePageVo;
import com.astral.qt.dto.vo.QtLikeSeqVo;
import com.astral.qt.dto.vo.QtUploadCompleteVo;
import com.astral.qt.dto.vo.QtUploadTicketVo;
import com.astral.qt.dto.vo.QtUserInfoVo;
import com.astral.qt.service.QtDakaService;
import com.astral.qt.service.QtLikeService;
import com.astral.qt.service.QtMediaService;
import com.astral.qt.service.QtUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.astral.log.annotation.LoginLog;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 轻听用户端控制器（App，新路径 /api/v1/app/user）
 * <p>认证方式：除白名单接口外，需携带 {@code satoken} 请求头（与管理系统统一走 Sa-Token）。</p>
 * <p>接口权限：登录后的业务接口由 {@code @RequiresPermission} 校验 App 端权限码
 * （{@code user:profile:*} / {@code user:like:*} / {@code user:daka:*}）；
 * 登录/注册/验证码/改密/刷新 token 属免认证白名单，logout 仅注销自身会话，均不设权限要求。</p>
 * <p>旧路径 /api/v1/user/** 已删除（客户端已全部迁移至此），不再提供兼容。</p>
 */
@Slf4j
@Tag(name = "轻听API-用户(App)")
@RestController
@RequestMapping("/api/v1/app/user")
public class QtAppUserController {

    @Resource
    private QtUserService userService;

    /** 登录/活跃标记：显式刷新 token 时回写 sys_user.login_time/login_ip */
    @Resource
    private UserLoginMarker userLoginMarker;

    @Resource
    private QtDakaService dakaService;

    @Resource
    private QtLikeService likeService;

    @Resource
    private QtMediaService mediaService;

    /**
     * 登录防爆破三件套与管理端对齐：IP 限流（@RateLimit）、失败锁定（service 层 LoginFailureStore）、
     * 登录日志（@LoginLog → sys_login_log）。明文密码依赖 HTTPS 传输（与行业惯例一致），
     * RSA 传输加密待客户端具备加密能力后再启用。
     */
    @Operation(summary = "用户登录")
    @RateLimit(key = "ip", limit = 5, duration = 60, message = "登录尝试过于频繁，请60秒后再试")
    @LoginLog("轻听App账号密码登录")
    @PostMapping("/login")
    public QtRestResp<QtUserInfoVo> login(@Valid @RequestBody QtLoginDto dto) {
        return QtRestResp.success(userService.login(dto));
    }

    @Operation(summary = "刷新 token")
    @PostMapping("/refresh")
    public QtRestResp<QtUserInfoVo> refresh(@RequestHeader(value = "satoken", required = true) String satoken,
                                            HttpServletRequest request) {
        // 检查当前 token 是否有效
        if (!StpUtil.isLogin()) {
            throw new com.astral.qt.common.QtException(401, "登录状态已失效");
        }
        // Sa-Token 的 token 过期前调用 getTokenValue() 会刷新过期时间（默认自动续期）
        // 这里返回新的 token 和过期时间
        String token = StpUtil.getTokenValue();
        long expiresIn = StpUtil.getTokenTimeout();
        QtUserInfoVo vo = new QtUserInfoVo();
        vo.setToken(token);
        vo.setExpiresIn(expiresIn);
        // 获取用户信息
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId != null) {
            Long userId = Long.parseLong(loginId.toString());
            // 显式刷新 token = 客户端仍活跃（App 通常在启动时调用）：回写最后活跃时间与 IP
            userLoginMarker.mark(userId, request);
            QtUserInfoVo userVo = userService.getUserInfoByToken(userId, token);
            vo.setUser(userVo.getUser());
            vo.setRoles(userVo.getRoles());
            vo.setPermissions(userVo.getPermissions());
        }
        return QtRestResp.success(vo);
    }

    @Operation(summary = "根据 token 获取用户信息")
    @RequiresPermission(value = "user:profile:view", name = "用户资料查看", description = "App 端查看本人资料、角色与权限列表")
    @GetMapping("/me")
    public QtRestResp<QtUserInfoVo> me(@RequestHeader(value = "satoken", required = false) String satoken) {
        Long userId = currentUserId(satoken);
        return QtRestResp.success(userService.getUserInfoByToken(userId, satoken));
    }

    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public QtRestResp<Void> logout(@RequestHeader(value = "satoken", required = false) String satoken) {
        Long userId = currentUserId(satoken);
        userService.logout(userId);
        return QtRestResp.success();
    }

    @Operation(summary = "发送邮箱验证码")
    @PostMapping("/email")
    public QtRestResp<Void> sendEmail(@Valid @RequestBody QtSendEmailDto dto) {
        userService.sendEmail(dto);
        return QtRestResp.success();
    }

    @Operation(summary = "用户注册")
    @RateLimit(key = "ip", limit = 5, duration = 60, message = "注册尝试过于频繁，请60秒后再试")
    @LoginLog("轻听App注册自动登录")
    @PostMapping("/register")
    public QtRestResp<QtUserInfoVo> register(@Valid @RequestBody QtRegisterDto dto) {
        return QtRestResp.success(userService.register(dto));
    }

    // ==================== 媒体直传（UPDATE_DESIGN.md §5，文件不经过 Astral 服务器） ====================

    @Operation(summary = "头像上传取直传凭证（仅修改时可用；每日次数由文件夹策略限制）")
    @RequiresPermission(value = "user:profile:edit", name = "用户资料编辑", description = "App 端修改本人资料、头像上传与直传回执")
    @PostMapping("/avatar/ticket")
    public QtRestResp<QtUploadTicketVo> avatarTicket(@RequestHeader(value = "satoken", required = false) String satoken,
                                                     @Valid @RequestBody QtAvatarTicketReqDto dto) {
        return QtRestResp.success(mediaService.avatarTicket(
                currentUserId(satoken), dto.getFileName(), dto.getContentType(), dto.getSizeBytes()));
    }

    @Operation(summary = "头像直传完成回执：核对登记后直写 sys_user.avatar")
    @RequiresPermission(value = "user:profile:edit", name = "用户资料编辑", description = "App 端修改本人资料、头像上传与直传回执")
    @PostMapping("/avatar/complete")
    public QtRestResp<QtUploadCompleteVo> avatarComplete(@RequestHeader(value = "satoken", required = false) String satoken,
                                                         @Valid @RequestBody QtUploadCompleteReqDto dto,
                                                         jakarta.servlet.http.HttpServletRequest request) {
        return QtRestResp.success(mediaService.avatarComplete(currentUserId(satoken), dto.getUploadId(), request));
    }

    @Operation(summary = "歌单封面上传取直传凭证（仅修改自己收藏的歌单；每日全部歌单合计限次）")
    @RequiresPermission(value = "user:like:edit", name = "收藏编辑", description = "App 端收藏/取消收藏（歌曲、歌单、批量）与歌单封面上传维护")
    @PostMapping("/like/playlist/{pid}/cover/ticket")
    public QtRestResp<QtUploadTicketVo> coverTicket(@RequestHeader(value = "satoken", required = false) String satoken,
                                                    @PathVariable String pid,
                                                    @Valid @RequestBody QtCoverTicketReqDto dto) {
        return QtRestResp.success(mediaService.coverTicket(
                currentUserId(satoken), pid, dto.getPlatform(),
                dto.getFileName(), dto.getContentType(), dto.getSizeBytes()));
    }

    @Operation(summary = "歌单封面直传完成回执：核对登记后直写 qt_like_playlist.pic_url")
    @RequiresPermission(value = "user:like:edit", name = "收藏编辑", description = "App 端收藏/取消收藏（歌曲、歌单、批量）与歌单封面上传维护")
    @PostMapping("/like/playlist/{pid}/cover/complete")
    public QtRestResp<QtUploadCompleteVo> coverComplete(@RequestHeader(value = "satoken", required = false) String satoken,
                                                        @PathVariable String pid,
                                                        @RequestParam("platform") String platform,
                                                        @Valid @RequestBody QtUploadCompleteReqDto dto,
                                                        jakarta.servlet.http.HttpServletRequest request) {
        return QtRestResp.success(mediaService.coverComplete(
                currentUserId(satoken), pid, platform, dto.getUploadId(), request));
    }

    @Operation(summary = "取消自定义歌单封面（恢复默认展示，不消耗每日次数）")
    @RequiresPermission(value = "user:like:edit", name = "收藏编辑", description = "App 端收藏/取消收藏（歌曲、歌单、批量）与歌单封面上传维护")
    @DeleteMapping("/like/playlist/{pid}/cover")
    public QtRestResp<Void> coverClear(@RequestHeader(value = "satoken", required = false) String satoken,
                                       @PathVariable String pid,
                                       @RequestParam("platform") String platform) {
        mediaService.coverClear(currentUserId(satoken), pid, platform);
        return QtRestResp.success();
    }

    @Operation(summary = "更新用户信息")
    @RequiresPermission(value = "user:profile:edit", name = "用户资料编辑", description = "App 端修改本人资料、头像上传与直传回执")
    @PostMapping("/update")
    public QtRestResp<QtUserInfoVo> update(@RequestHeader(value = "satoken", required = false) String satoken,
                                           @Valid @RequestBody QtUpdateUserDto dto) {
        Long userId = currentUserId(satoken);
        User user = userService.updateUser(userId, dto);
        QtUserInfoVo vo = new QtUserInfoVo();
        vo.setUser(user);
        return QtRestResp.success(vo);
    }

    @Operation(summary = "通过邮箱验证码重置密码")
    @PostMapping("/changePass")
    public QtRestResp<User> changePwByEmail(@Valid @RequestBody QtChangePwByEmailDto dto) {
        return QtRestResp.success(userService.changePwByEmail(dto));
    }

    @Operation(summary = "自助注销账号（凭密码确认，注销后停用+匿名化+全端下线）")
    @RequiresPermission(value = "user:profile:deactivate", name = "自助注销",
            description = "App 用户注销自己的账号（停用+匿名化）")
    @PostMapping("/deactivate")
    public QtRestResp<Void> deactivate(@RequestHeader(value = "satoken", required = false) String satoken,
                                       @Valid @RequestBody QtDeactivateDto dto) {
        userService.deactivate(currentUserId(satoken), dto.getPassword());
        return QtRestResp.success();
    }

    @Operation(summary = "用户签到")
    @RequiresPermission(value = "user:daka:submit", name = "签到提交", description = "App 端执行每日签到")
    @PostMapping("/daka")
    public QtRestResp<Void> daka(@RequestHeader(value = "satoken", required = false) String satoken,
                                 @Valid @RequestBody QtUserDakaDto dto) {
        dakaService.daka(currentUserId(satoken), dto);
        return QtRestResp.success();
    }

    @Operation(summary = "获取连续签到天数和总有效积分")
    @RequiresPermission(value = "user:daka:view", name = "签到查看", description = "App 端查看签到天数、积分与月度签到详情")
    @GetMapping("/dakaInfo")
    public QtRestResp<QtDakaDaysAndCodeVo> dakaInfo(@RequestHeader(value = "satoken", required = false) String satoken) {
        return QtRestResp.success(dakaService.getDakaDaysAndCode(currentUserId(satoken)));
    }

    @Operation(summary = "获取某年某月签到详情")
    @RequiresPermission(value = "user:daka:view", name = "签到查看", description = "App 端查看签到天数、积分与月度签到详情")
    @GetMapping("/dakaInfoByMonth")
    public QtRestResp<List<String>> dakaInfoByMonth(@RequestHeader(value = "satoken", required = false) String satoken,
                                                    @RequestParam("time") @NotBlank String time) {
        return QtRestResp.success(dakaService.getDakaInfoByMonth(currentUserId(satoken), time));
    }

    // ==================== 收藏同步接口（LIKE_SYNC_DESIGN.md §2） ====================

    @Operation(summary = "收藏/取消收藏单曲")
    @RequiresPermission(value = "user:like:edit", name = "收藏编辑", description = "App 端收藏/取消收藏（歌曲、歌单、批量）与歌单封面上传维护")
    @PostMapping("/like/song")
    public QtRestResp<QtLikeSeqVo> likeSong(@RequestHeader(value = "satoken", required = false) String satoken,
                                            @Valid @RequestBody QtLikeSongActionDto dto) {
        return QtRestResp.success(likeService.likeSong(currentUserId(satoken), dto));
    }

    @Operation(summary = "收藏/取消收藏歌单")
    @RequiresPermission(value = "user:like:edit", name = "收藏编辑", description = "App 端收藏/取消收藏（歌曲、歌单、批量）与歌单封面上传维护")
    @PostMapping("/like/playlist")
    public QtRestResp<QtLikeSeqVo> likePlaylist(@RequestHeader(value = "satoken", required = false) String satoken,
                                                @Valid @RequestBody QtLikePlaylistActionDto dto) {
        return QtRestResp.success(likeService.likePlaylist(currentUserId(satoken), dto));
    }

    @Operation(summary = "批量收藏/取消（歌曲+歌单混排，单批最多200）")
    @RequiresPermission(value = "user:like:edit", name = "收藏编辑", description = "App 端收藏/取消收藏（歌曲、歌单、批量）与歌单封面上传维护")
    @PostMapping("/like/batch")
    public QtRestResp<QtLikeSeqVo> likeBatch(@RequestHeader(value = "satoken", required = false) String satoken,
                                             @Valid @RequestBody QtLikeBatchDto dto) {
        return QtRestResp.success(likeService.likeBatch(currentUserId(satoken), dto));
    }

    @Operation(summary = "增量拉取收藏变更")
    @RequiresPermission(value = "user:like:view", name = "收藏查看", description = "App 端拉取收藏歌单/歌曲（全量、分页、增量）")
    @GetMapping("/like/changes")
    public QtRestResp<QtLikeChangesVo> getLikeChanges(@RequestHeader(value = "satoken", required = false) String satoken,
                                                      @RequestParam(value = "since", required = false, defaultValue = "0") long since) {
        if (since < 0) {
            throw new QtException("since不能为负数");
        }
        return QtRestResp.success(likeService.getChanges(currentUserId(satoken), since));
    }

    @Operation(summary = "全量分页拉取收藏")
    @RequiresPermission(value = "user:like:view", name = "收藏查看", description = "App 端拉取收藏歌单/歌曲（全量、分页、增量）")
    @GetMapping("/like/list")
    public QtRestResp<QtLikePageVo> getLikePage(@RequestHeader(value = "satoken", required = false) String satoken,
                                                @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                                                @RequestParam(value = "size", required = false, defaultValue = "500") Integer size) {
        return QtRestResp.success(likeService.getLikePage(currentUserId(satoken), page, size));
    }

    private Long currentUserId(String satoken) {
        // 优先读取全局统一认证拦截器（AuthInterceptor）写入的 userId attribute（避免重复调用 Sa-Token 上下文解析）
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
