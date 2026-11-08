package com.astral.auth.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.auth.dto.TokenInfo;
import com.astral.auth.security.RsaKeyManager;
import com.astral.auth.security.TotpUtil;
import com.astral.auth.service.TokenService;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.common.util.PasswordPolicy;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 个人中心（管理端自助操作，登录即可，不挂权限码）
 *
 * <p>全部是「对当前登录人自己」的操作：改自己的密码 / 绑定 TOTP / 看自己的会话，
 * 与 AGENTS.md §5 的权限码判据一致——不涉及他人账号、不构成提权面，
 * 安全性由「旧密码校验 / TOTP 校验」保障而非角色模型。鉴权沿用
 * {@code /api/v1/admin/**} 的管理端登录门禁（AuthInterceptor + ADMIN 身份）。</p>
 */
@Slf4j
@Tag(name = "个人中心")
@RestController
@RequestMapping("/api/v1/admin/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserMapper userMapper;
    private final RsaKeyManager rsaKeyManager;
    private final TokenService tokenService;

    // ------------------------------------------------------------------
    // 基本信息
    // ------------------------------------------------------------------

    @Operation(summary = "我的资料")
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        long userId = StpUtil.getLoginIdAsLong();
        User user = requireUser(userId);
        Map<String, Object> data = new HashMap<>();
        data.put("id", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("email", user.getEmail());
        data.put("userType", user.getUserType());
        data.put("loginIp", user.getLoginIp());
        data.put("loginTime", user.getLoginTime());
        data.put("pwdUpdateTime", user.getPwdUpdateTime());
        data.put("totpEnabled", user.getTotpEnabled() != null && user.getTotpEnabled() == 1);
        data.put("mustChangePassword", user.getMustChangePassword() != null && user.getMustChangePassword() == 1);
        return Result.success(data);
    }

    // ------------------------------------------------------------------
    // 修改密码
    // ------------------------------------------------------------------

    @Data
    public static class ChangePasswordRequest {
        @NotBlank(message = "旧密码不能为空")
        private String oldPassword;

        @NotBlank(message = "新密码不能为空")
        private String newPassword;
    }

    @Operation(summary = "修改自己的密码（旧密码校验 + 强度策略 + 全端踢下线）")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = requireUser(userId);

        String oldPlain = decrypt(body.getOldPassword());
        String newPlain = decrypt(body.getNewPassword());
        if (!BCrypt.checkpw(oldPlain, user.getPassword())) {
            throw new BusinessException("AUTH002");
        }
        if (oldPlain.equals(newPlain)) {
            throw BusinessException.of("新密码不能与旧密码相同");
        }
        // 强度策略（失败抛 IllegalArgumentException -> HTTP 200 + 业务码 320 参数错误）
        PasswordPolicy.validateOrThrow(newPlain, user.getUsername());

        requireChanged(userMapper.update(
                null,
                Wrappers.<User>lambdaUpdate()
                        .eq(User::getId, userId)
                        .eq(User::getPassword, user.getPassword())
                        .set(User::getPassword, BCrypt.hashpw(newPlain))
                        .set(User::getPwdUpdateTime, LocalDateTime.now())
                        .set(User::getMustChangePassword, 0)
                        .set(User::getUpdateTime, LocalDateTime.now())));
        // 改密后全端踢下线（含当前会话），前端收到成功后跳登录页重新认证
        StpUtil.kickout(userId);
        log.info("[profile] 用户 {} 修改密码成功，全部会话已失效", user.getUsername());
        return Result.success();
    }

    // ------------------------------------------------------------------
    // 我的会话
    // ------------------------------------------------------------------

    @Operation(summary = "我的在线会话")
    @GetMapping("/sessions")
    public Result<Page<TokenInfo>> mySessions(
            @RequestParam(defaultValue = "1") Integer pageNum, @RequestParam(defaultValue = "10") Integer pageSize) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.success(tokenService.pageFromSaToken(pageNum, pageSize, userId));
    }

    /**
     * 下线我的指定会话。
     *
     * <p>路径参数是<b>会话标识（token 指纹）</b>，不是明文 token：路径会进 access log /
     * 反向代理日志 / 浏览器历史，把凭据放进去等于写日志即泄漏。指纹由
     * {@code GET /sessions} 的 {@code id}/{@code token} 字段给出，同一 token 恒定。</p>
     */
    @Operation(summary = "下线我的指定会话（仅限本人会话，先校验归属）")
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> revokeMySession(@PathVariable String sessionId) {
        long userId = StpUtil.getLoginIdAsLong();
        String token = tokenService.resolveTokenBySessionId(sessionId);
        if (token == null) {
            throw BusinessException.of("会话不存在或已失效");
        }
        Object owner = StpUtil.getLoginIdByToken(token);
        if (owner == null) {
            throw BusinessException.of("会话不存在或已失效");
        }
        if (!String.valueOf(userId).equals(String.valueOf(owner))) {
            // 越权防护：只能下线自己的 token
            throw BusinessException.of("只能下线自己的会话");
        }
        StpUtil.logoutByTokenValue(token);
        return Result.success();
    }

    // ------------------------------------------------------------------
    // TOTP 二次验证
    // ------------------------------------------------------------------

    @Operation(summary = "生成 TOTP 密钥（未启用状态，绑定后生效）")
    @PostMapping("/totp/setup")
    public Result<Map<String, String>> totpSetup() {
        long userId = StpUtil.getLoginIdAsLong();
        User user = requireUser(userId);
        if (user.getTotpEnabled() != null && user.getTotpEnabled() == 1) {
            throw BusinessException.of("TOTP 已启用，请先关闭再重新绑定");
        }
        String secret = TotpUtil.generateSecret();
        requireChanged(userMapper.update(
                null,
                credentialState(user)
                        .set(User::getTotpSecret, secret)
                        .set(User::getTotpEnabled, 0)
                        .set(User::getUpdateTime, LocalDateTime.now())));
        Map<String, String> data = new HashMap<>();
        data.put("secret", secret);
        data.put("otpauthUri", TotpUtil.buildOtpAuthUri(secret, user.getUsername(), "Astral"));
        return Result.success(data);
    }

    @Data
    public static class TotpEnableRequest {
        @NotBlank(message = "动态验证码不能为空")
        private String code;
    }

    @Operation(summary = "确认启用 TOTP（校验动态码后生效）")
    @PostMapping("/totp/enable")
    public Result<Void> totpEnable(@Valid @RequestBody TotpEnableRequest body) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = requireUser(userId);
        if (user.getTotpSecret() == null || user.getTotpSecret().isBlank()) {
            throw BusinessException.of("请先生成 TOTP 密钥");
        }
        if (!TotpUtil.verify(body.getCode(), user.getTotpSecret())) {
            throw new BusinessException("AUTH011");
        }
        requireChanged(userMapper.update(
                null,
                credentialState(user).set(User::getTotpEnabled, 1).set(User::getUpdateTime, LocalDateTime.now())));
        log.info("[profile] 用户 {} 已启用 TOTP 二次验证", user.getUsername());
        return Result.success();
    }

    /**
     * 关闭 TOTP 的请求体。
     *
     * <p>不复用 {@link ChangePasswordRequest}：那个 DTO 的 {@code newPassword} 也带
     * {@code @NotBlank}，复用时关闭 TOTP 必须凭空传一个「新密码」而服务端根本不读它，
     * 客户端只能塞垃圾值（或被自己的强度校验拦住），契约与实现不一致。</p>
     */
    @Data
    public static class TotpDisableRequest {
        @NotBlank(message = "登录密码不能为空")
        private String oldPassword;
    }

    @Operation(summary = "关闭 TOTP（需校验登录密码）")
    @PostMapping("/totp/disable")
    public Result<Void> totpDisable(@Valid @RequestBody TotpDisableRequest body) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = requireUser(userId);
        String plain = decrypt(body.getOldPassword());
        if (!BCrypt.checkpw(plain, user.getPassword())) {
            throw new BusinessException("AUTH002");
        }
        // MP 默认 NOT_NULL 策略下 updateById 跳过 null 字段，清空 secret 必须走 lambdaUpdate 显式 set
        requireChanged(userMapper.update(
                null,
                credentialState(user)
                        .set(User::getTotpEnabled, 0)
                        .set(User::getTotpSecret, null)
                        .set(User::getUpdateTime, LocalDateTime.now())));
        log.info("[profile] 用户 {} 已关闭 TOTP 二次验证", user.getUsername());
        return Result.success();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private User requireUser(long userId) {
        User user = userMapper.selectById(userId);
        if (user == null || (user.getDeleted() != null && user.getDeleted() == 1)) {
            throw new BusinessException("SYS001");
        }
        return user;
    }

    private static com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<User> credentialState(
            User user) {
        return Wrappers.<User>lambdaUpdate()
                .eq(User::getId, user.getId())
                .eq(User::getPassword, user.getPassword())
                .eq(user.getTotpSecret() != null, User::getTotpSecret, user.getTotpSecret())
                .isNull(user.getTotpSecret() == null, User::getTotpSecret)
                .eq(user.getTotpEnabled() != null, User::getTotpEnabled, user.getTotpEnabled())
                .isNull(user.getTotpEnabled() == null, User::getTotpEnabled);
    }

    private static void requireChanged(int affected) {
        if (affected != 1) throw BusinessException.of("账号凭据已变更，请刷新后重新验证");
    }

    /**
     * RSA 解密（前端经 encryptPassword 加密，与登录同一通道）。
     *
     * <p>失败统一抛 AUTH002（与「密码错误」同一错误码）：单独给「密码解密失败」（AUTH004）
     * 会让调用方能区分「密文本身有问题」与「密码不对」，是登录链路上的填充 oracle 的一半。</p>
     */
    private String decrypt(String encrypted) {
        try {
            return rsaKeyManager.decryptPasswordBase64(encrypted);
        } catch (Exception e) {
            throw new BusinessException("AUTH002");
        }
    }
}
