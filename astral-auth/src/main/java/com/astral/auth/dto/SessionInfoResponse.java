package com.astral.auth.dto;

import java.util.List;

/** 已登录的会话信息，只包含身份、权限和双提交 CSRF 值，不重新暴露认证凭据。 */
public record SessionInfoResponse(
        Long id,
        String username,
        String nickname,
        List<String> roles,
        List<String> permissions,
        String userType,
        Integer mustChangePassword,
        String csrfToken) {
    public SessionInfoResponse {
        roles = roles == null ? List.of() : List.copyOf(roles);
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        mustChangePassword = mustChangePassword == null ? 0 : mustChangePassword;
    }
}
