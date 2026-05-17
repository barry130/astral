package com.astral.auth.service.impl;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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