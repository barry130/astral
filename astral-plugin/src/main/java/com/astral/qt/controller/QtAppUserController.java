package com.astral.qt.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.dao.entity.User;
import com.astral.qt.common.QtException;
import com.astral.qt.common.QtRestResp;
import com.astral.qt.dto.QtChangePwByEmailDto;
import com.astral.qt.dto.QtLikePlaylistActionDto;
import com.astral.qt.dto.QtLikeSongActionDto;
import com.astral.qt.dto.QtLoginDto;
import com.astral.qt.dto.QtRegisterDto;
import com.astral.qt.dto.QtSendEmailDto;
import com.astral.qt.dto.QtUpdateUserDto;
import com.astral.qt.dto.QtUserDakaDto;
import com.astral.qt.dto.QtUploadLikeListDto;
import com.astral.qt.dto.vo.QtDakaDaysAndCodeVo;
import com.astral.qt.dto.vo.QtDataVo;
import com.astral.qt.dto.vo.QtLikeChangesVo;
import com.astral.qt.dto.vo.QtLikeListVo;
import com.astral.qt.dto.vo.QtLikePageVo;
import com.astral.qt.dto.vo.QtLikeSeqVo;
import com.astral.qt.dto.vo.QtUserInfoVo;
import com.astral.qt.service.QtDakaService;
import com.astral.qt.service.QtLikeService;
import com.astral.qt.service.QtUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 轻听用户端控制器（App，新路径 /api/v1/app/user）
 * <p>认证方式：除白名单接口外，需携带 {@code satoken} 请求头（与管理系统统一走 Sa-Token）。</p>
 * <p>旧路径 /api/v1/user/** 保留并废弃（见 {@link QtUserController}），App 请迁移至此。</p>
 */
@Slf4j
@Tag(name = "轻听API-用户(App)")
@RestController
@RequestMapping("/api/v1/app/user")
public class QtAppUserController {

    @Resource
    private QtUserService userService;

    @Resource
    private QtDakaService dakaService;

    @Resource
    private QtLikeService likeService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public QtRestResp<QtUserInfoVo> login(@Valid @RequestBody QtLoginDto dto) {
        return QtRestResp.success(userService.login(dto));
    }

    @Operation(summary = "刷新 token")
    @PostMapping("/refresh")
    public QtRestResp<QtUserInfoVo> refresh(@RequestHeader(value = "satoken", required = true) String satoken) {
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
            QtUserInfoVo userVo = userService.getUserInfoByToken(userId, token);
            vo.setUser(userVo.getUser());
            vo.setRoles(userVo.getRoles());
            vo.setPermissions(userVo.getPermissions());
        }
        return QtRestResp.success(vo);
    }

    @Operation(summary = "根据 token 获取用户信息")
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
    @PostMapping("/register")
    public QtRestResp<QtUserInfoVo> register(@Valid @RequestBody QtRegisterDto dto) {
        return QtRestResp.success(userService.register(dto));
    }

    @Operation(summary = "上传头像")
    @PostMapping("/upload")
    public QtRestResp<QtDataVo<String>> upload(@RequestParam("avatar") MultipartFile file) {
        return QtRestResp.success(userService.upload(file));
    }

    @Operation(summary = "更新用户信息")
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

    @Operation(summary = "用户签到")
    @PostMapping("/daka")
    public QtRestResp<Void> daka(@RequestHeader(value = "satoken", required = false) String satoken,
                                 @Valid @RequestBody QtUserDakaDto dto) {
        dakaService.daka(currentUserId(satoken), dto);
        return QtRestResp.success();
    }

    @Operation(summary = "获取连续签到天数和总有效积分")
    @GetMapping("/dakaInfo")
    public QtRestResp<QtDakaDaysAndCodeVo> dakaInfo(@RequestHeader(value = "satoken", required = false) String satoken) {
        return QtRestResp.success(dakaService.getDakaDaysAndCode(currentUserId(satoken)));
    }

    @Operation(summary = "获取某年某月签到详情")
    @GetMapping("/dakaInfoByMonth")
    public QtRestResp<List<String>> dakaInfoByMonth(@RequestHeader(value = "satoken", required = false) String satoken,
                                                    @RequestParam("time") @NotBlank String time) {
        return QtRestResp.success(dakaService.getDakaInfoByMonth(currentUserId(satoken), time));
    }

    /**
     * @deprecated 旧全量拉取（LIKE_SYNC_DESIGN.md §3）。新客户端请用
     *              {@link #getLikePage}（全量分页）或 {@link #getLikeChanges}（增量）。
     */
    @Deprecated
    @Operation(summary = "获取用户收藏歌单+歌曲", deprecated = true)
    @GetMapping("/getLikeList")
    public QtRestResp<QtLikeListVo> getLikeList(@RequestHeader(value = "satoken", required = false) String satoken) {
        return QtRestResp.success(likeService.getLikeList(currentUserId(satoken)));
    }

    /**
     * @deprecated 旧全量同步（LIKE_SYNC_DESIGN.md §3）。新客户端请用
     *              {@link #likeSong} / {@link #likePlaylist}（单条收藏）。
     */
    @Deprecated
    @Operation(summary = "同步收藏歌单+歌曲", deprecated = true)
    @PostMapping("/uploadLikeList")
    public QtRestResp<Void> uploadLikeList(@RequestHeader(value = "satoken", required = false) String satoken,
                                           @Valid @RequestBody QtUploadLikeListDto dto) {
        likeService.uploadLikeList(currentUserId(satoken), dto);
        return QtRestResp.success();
    }

    // ==================== 收藏同步新接口（LIKE_SYNC_DESIGN.md §2） ====================

    @Operation(summary = "收藏/取消收藏单曲")
    @PostMapping("/like/song")
    public QtRestResp<QtLikeSeqVo> likeSong(@RequestHeader(value = "satoken", required = false) String satoken,
                                            @Valid @RequestBody QtLikeSongActionDto dto) {
        return QtRestResp.success(likeService.likeSong(currentUserId(satoken), dto));
    }

    @Operation(summary = "收藏/取消收藏歌单")
    @PostMapping("/like/playlist")
    public QtRestResp<QtLikeSeqVo> likePlaylist(@RequestHeader(value = "satoken", required = false) String satoken,
                                                @Valid @RequestBody QtLikePlaylistActionDto dto) {
        return QtRestResp.success(likeService.likePlaylist(currentUserId(satoken), dto));
    }

    @Operation(summary = "增量拉取收藏变更")
    @GetMapping("/like/changes")
    public QtRestResp<QtLikeChangesVo> getLikeChanges(@RequestHeader(value = "satoken", required = false) String satoken,
                                                      @RequestParam(value = "since", required = false, defaultValue = "0") long since) {
        if (since < 0) {
            throw new QtException("since不能为负数");
        }
        return QtRestResp.success(likeService.getChanges(currentUserId(satoken), since));
    }

    @Operation(summary = "全量分页拉取收藏")
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
