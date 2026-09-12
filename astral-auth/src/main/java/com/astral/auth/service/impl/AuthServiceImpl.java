package com.astral.auth.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.astral.auth.dto.LoginRequest;
import com.astral.auth.dto.LoginResponse;
import com.astral.auth.security.RsaKeyManager;
import com.astral.auth.service.AuthService;
import com.astral.common.error.ErrorCodes;
import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
public class AuthServiceImpl implements AuthService {
    @Autowired
    private UserMapper userMapper;
    
    @Autowired
    private RsaKeyManager rsaKeyManager;
    
    @Autowired
    private StpInterface stpInterface;

    @Override
    public LoginResponse login(LoginRequest request) {
        if (rsaKeyManager.isAccountLocked(request.getUsername())) {
            throw new BusinessException("AUTH003");
        }
        
        User user = userMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<User>()
                .eq("username", request.getUsername())
                .eq("deleted", 0)
        );
        
        if (user == null) {
            rsaKeyManager.recordLoginFailure(request.getUsername());
            throw new BusinessException("AUTH002");
        }
        
        String password = request.getPassword();
        try {
            password = rsaKeyManager.decryptPasswordBase64(password);
        } catch (Exception e) {
            throw new BusinessException("AUTH004");
        }
        
        if (!BCrypt.checkpw(password, user.getPassword())) {
            rsaKeyManager.recordLoginFailure(request.getUsername());
            throw new BusinessException("AUTH002");
        }
        
rsaKeyManager.resetLoginFailures(request.getUsername());

        StpUtil.login(user.getId());
        // 将用户名存入 Sa-Token 会话，供 AuthInterceptor 直接读取，避免每次请求查库
        StpUtil.getSession().set("username", user.getUsername());
        StpUtil.getSession().set("nickname", user.getNickname());
        // 将登录IP和登录时间存入Token会话，供Token管理页面读取
        SaSession tokenSession = StpUtil.getTokenSession();
        tokenSession.set("loginIp", getClientIp());
        tokenSession.set("loginTime", LocalDateTime.now());
        String token = StpUtil.getTokenValue();
        
        List<String> roles = stpInterface.getRoleList(user.getId(), null);
        List<String> permissions = stpInterface.getPermissionList(user.getId(), null);
        
        LoginResponse response = new LoginResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setToken(token);
        response.setRoles(roles != null ? roles : Collections.emptyList());
        response.setPermissions(permissions != null ? permissions : Collections.emptyList());
        return response;
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 获取客户端真实IP地址
     * <p>依次尝试 X-Forwarded-For、X-Real-IP、RemoteAddr</p>
     *
     * @return 客户端IP地址，获取失败返回null
     */
    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                String ip = req.getHeader("X-Forwarded-For");
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = req.getHeader("X-Real-IP");
                }
                if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
                    ip = req.getRemoteAddr();
                }
                // X-Forwarded-For 可能包含多个IP，取第一个（最原始的客户端IP）
                if (ip != null && ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Override
public LoginResponse getLoginInfo() {
        if (!StpUtil.isLogin()) {
            throw new BusinessException("AUTH001");
        }

        long userId = StpUtil.getLoginIdAsLong();
        User user = userMapper.selectById(userId);

        if (user == null) {
            throw new BusinessException("SYS001");
        }

        // 刷新会话中的用户信息
        StpUtil.getSession().set("username", user.getUsername());
        StpUtil.getSession().set("nickname", user.getNickname());
        
        List<String> roles = stpInterface.getRoleList(userId, null);
        List<String> permissions = stpInterface.getPermissionList(userId, null);
        
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