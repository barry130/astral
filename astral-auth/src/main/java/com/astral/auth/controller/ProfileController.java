package com.astral.auth.controller;

import cn.hutool.crypto.digest.BCrypt;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.dto.TokenInfo;
import com.astral.auth.security.RsaKeyManager;
import com.astral.auth.security.TotpUtil;
import com.astral.auth.service.TokenService;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.common.util.PasswordPolicy;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

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
            return Result.fail("新密码不能与旧密码相同");
        }
        // 强度策略（失败抛 IllegalArgumentException -> 400 文案）
        PasswordPolicy.validateOrThrow(newPlain, user.getUsername());

        user.setPassword(BCrypt.hashpw(newPlain));
        user.setPwdUpdateTime(LocalDateTime.now());
        user.setMustChangePassword(0);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
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
    public Result<Page<TokenInfo>> mySessions(@RequestParam(defaultValue = "1") Integer pageNum,
                                              @RequestParam(defaultValue = "10") Integer pageSize) {
        long userId = StpUtil.getLoginIdAsLong();
        return Result.success(tokenService.pageFromSaToken(pageNum, pageSize, userId));
    }

    @Operation(summary = "下线我的指定会话（仅限本人 token，先校验归属）")
    @DeleteMapping("/sessions/{token}")
    public Result<Void> revokeMySession(@PathVariable String token) {
        long userId = StpUtil.getLoginIdAsLong();
        Object owner = StpUtil.getLoginIdByToken(token);
        if (owner == null) {
            return Result.fail("会话不存在或已失效");
        }
        if (!String.valueOf(userId).equals(String.valueOf(owner))) {
            // 越权防护：只能下线自己的 token
            return Result.fail("只能下线自己的会话");
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
            return Result.fail("TOTP 已启用，请先关闭再重新绑定");
        }
        String secret = TotpUtil.generateSecret();
        user.setTotpSecret(secret);
        user.setTotpEnabled(0);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
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
            return Result.fail("请先生成 TOTP 密钥");
        }
        if (!TotpUtil.verify(body.getCode(), user.getTotpSecret())) {
            throw new BusinessException("AUTH011");
        }
        user.setTotpEnabled(1);
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateById(user);
        log.info("[profile] 用户 {} 已启用 TOTP 二次验证", user.getUsername());
        return Result.success();
    }

    @Operation(summary = "关闭 TOTP（需校验登录密码）")
    @PostMapping("/totp/disable")
    public Result<Void> totpDisable(@Valid @RequestBody ChangePasswordRequest body) {
        long userId = StpUtil.getLoginIdAsLong();
        User user = requireUser(userId);
        String plain = decrypt(body.getOldPassword());
        if (!BCrypt.checkpw(plain, user.getPassword())) {
            throw new BusinessException("AUTH002");
        }
        // MP 默认 NOT_NULL 策略下 updateById 跳过 null 字段，清空 secret 必须走 lambdaUpdate 显式 set
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getTotpEnabled, 0)
                .set(User::getTotpSecret, null)
                .set(User::getUpdateTime, LocalDateTime.now()));
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

    /** RSA 解密（前端经 encryptPassword 加密，与登录同一通道） */
    private String decrypt(String encrypted) {
        try {
            return rsaKeyManager.decryptPasswordBase64(encrypted);
        } catch (Exception e) {
            throw new BusinessException("AUTH004");
        }
    }
}
