package com.astral.auth.api;

import com.astral.auth.dto.LoginRequest;
import com.astral.auth.dto.LoginResponse;
import com.astral.auth.security.RsaKeyManager;
import com.astral.auth.service.AuthService;
import com.astral.common.annotation.RateLimit;
import com.astral.common.result.Result;
import com.astral.log.annotation.LoginLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证控制器（通用路径 /api/v1/all/auth，管理端与 App 端共用）。
 * <p>旧接口 /api/v1/auth/** 保留并标记废弃，App 端迁移到 /api/v1/all/auth/**。</p>
 */
@RestController
@RequestMapping("/api/v1/all/auth")
@RequiredArgsConstructor
public class AllAuthController {

    private final AuthService authService;
    private final RsaKeyManager rsaKeyManager;

    @GetMapping("/public-key")
    public Result<Map<String, String>> getPublicKey() {
        Map<String, String> result = new HashMap<>();
        result.put("publicKey", rsaKeyManager.getPublicKeyBase64());
        return Result.success(result);
    }

    @RateLimit(key = "ip", limit = 5, duration = 60, message = "登录尝试次数过多，请60秒后再试")
    @LoginLog("用户名密码登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        Result<LoginResponse> result = Result.success(authService.login(request));
        if (result.getData() != null) {
            httpRequest.setAttribute("username", result.getData().getUsername());
        }
        return result;
    }

    /**
     * 用户登出接口
     * <p>使当前用户的Token失效，清除会话信息。</p>
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    /**
     * 获取当前登录用户信息
     * <p>返回当前已登录用户的详细信息，包括角色和权限列表。</p>
     */
    @GetMapping("/info")
    public Result<LoginResponse> getUserInfo() {
        return Result.success(authService.getLoginInfo());
    }
}
