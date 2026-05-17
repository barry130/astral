package com.astral.auth.api;

import com.astral.auth.dto.LoginRequest;
import com.astral.auth.dto.LoginResponse;
import com.astral.auth.service.AuthService;
import com.astral.common.result.Result;
import com.astral.log.annotation.LoginLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器
 * <p>
 * 提供用户认证相关的REST接口，包括登录、登出、获取用户信息。
 * 接口路径前缀：{@code /api/v1/auth}
 * </p>
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    /** 认证服务 */
    private final AuthService authService;

    /**
     * 用户登录接口
     * <p>
     * 标注了 {@link LoginLog} 注解，登录成功后会自动记录登录日志。
     * 同时将用户名设置到请求属性中，供登录日志切面使用。
     * </p>
     *
     * @param request    登录请求，包含用户名和密码
     * @param httpRequest HTTP请求对象，用于设置用户名属性
     * @return 登录响应，包含用户信息和认证令牌
     */
    @LoginLog("用户名密码登录")
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request, jakarta.servlet.http.HttpServletRequest httpRequest) {
        Result<LoginResponse> result = Result.success(authService.login(request));
        if (result.getData() != null) {
            httpRequest.setAttribute("username", result.getData().getUsername());
        }
        return result;
    }

    /**
     * 用户登出接口
     * <p>
     * 使当前用户的Token失效，清除会话信息。
     * </p>
     *
     * @return 操作结果
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }

    /**
     * 获取当前登录用户信息
     * <p>
     * 返回当前已登录用户的详细信息，包括角色和权限列表。
     * 需要携带有效的认证Token才能访问。
     * </p>
     *
     * @return 用户信息
     */
    @GetMapping("/info")
    public Result<LoginResponse> getUserInfo() {
        return Result.success(authService.getLoginInfo());
    }
}