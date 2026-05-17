package com.astral.auth.service;

import com.astral.auth.dto.LoginRequest;
import com.astral.auth.dto.LoginResponse;

/**
 * 认证服务接口
 * <p>
 * 提供用户登录、登出、获取当前登录信息等核心认证功能。
 * </p>
 */
public interface AuthService {
    /**
     * 用户登录
     * <p>
     * 验证用户名和密码，校验通过后创建Sa-Token会话并返回令牌。
     * </p>
     *
     * @param request 登录请求，包含用户名和密码
     * @return 登录响应，包含用户信息和认证令牌
     * @throws com.astral.common.exception.BusinessException 用户不存在或密码错误时抛出
     */
    LoginResponse login(LoginRequest request);

    /**
     * 用户登出
     * <p>
     * 使当前用户的Sa-Token会话失效。
     * </p>
     */
    void logout();

    /**
     * 获取当前登录用户信息
     * <p>
     * 从Sa-Token会话中获取当前登录用户的ID，查询用户详情并返回。
     * </p>
     *
     * @return 登录用户信息
     * @throws com.astral.common.exception.BusinessException 未登录或用户不存在时抛出
     */
    LoginResponse getLoginInfo();
}