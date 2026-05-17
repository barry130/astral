package com.astral.auth.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.auth.dto.LoginRequest;
import com.astral.auth.dto.LoginResponse;
import com.astral.auth.service.AuthService;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

/**
 * 认证服务实现类
 * <p>
 * 基于Sa-Token实现用户认证功能，包括：
 * <ul>
 *   <li>用户名密码登录：校验密码后创建Token会话</li>
 *   <li>登出：使当前Token失效</li>
 *   <li>获取登录信息：从会话中获取当前用户详情</li>
 * </ul>
 * </p>
 * <p>
 * 密码校验使用 Hutool BCrypt 算法，确保密码存储安全。
 * </p>
 */
@Service
public class AuthServiceImpl implements AuthService {
    /** 用户Mapper，用于查询用户信息 */
    @Autowired
    private UserMapper userMapper;

    /**
     * 用户登录
     * <p>
     * 执行流程：
     * 1. 根据用户名查询用户（排除已逻辑删除的用户）
     * 2. 使用BCrypt校验密码
     * 3. 调用 StpUtil.login() 创建会话
     * 4. 从会话中获取角色和权限列表
     * 5. 组装登录响应返回
     * </p>
     *
     * @param request 登录请求
     * @return 登录响应
     * @throws BusinessException 用户不存在或密码错误时抛出
     */
    @Override
    public LoginResponse login(LoginRequest request) {
        User user = userMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                .eq("username", request.getUsername())
                .eq("deleted", 0)
        );
        
        if (user == null) {
            throw new BusinessException("User not found");
        }
        
        if (!BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new BusinessException("Wrong password");
        }
        
        StpUtil.login(user.getId());
        String token = StpUtil.getTokenValue();
        
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) StpUtil.getSession().get("roles");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) StpUtil.getSession().get("permissions");
        
        LoginResponse response = new LoginResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setToken(token);
        response.setRoles(roles != null ? roles : Collections.emptyList());
        response.setPermissions(permissions != null ? permissions : Collections.emptyList());
        return response;
    }

    /**
     * 用户登出
     * <p>
     * 调用 StpUtil.logout() 使当前Token失效，清除会话信息。
     * </p>
     */
    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 获取当前登录用户信息
     * <p>
     * 执行流程：
     * 1. 检查当前是否已登录
     * 2. 从会话中获取用户ID
     * 3. 查询用户详情
     * 4. 从会话中获取角色和权限列表
     * 5. 组装响应返回
     * </p>
     *
     * @return 登录用户信息
     * @throws BusinessException 未登录或用户不存在时抛出
     */
    @Override
    public LoginResponse getLoginInfo() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException(401, "Not logged in");
        }
        
        long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);
        
        if (user == null) {
            throw new BusinessException("User not found");
        }
        
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) StpUtil.getSession().get("roles");
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) StpUtil.getSession().get("permissions");
        
        LoginResponse response = new LoginResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setToken(StpUtil.getTokenValue());
        response.setRoles(roles != null ? roles : Collections.emptyList());
        response.setPermissions(permissions != null ? permissions : Collections.emptyList());
        return response;
    }
}