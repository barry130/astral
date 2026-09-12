package com.astral.auth.security;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 管理端权限校验器（RBAC：用户 -> 角色 -> 权限）
 * <p>后端接口校验权限编码，配合 sys_permission / sys_role_permission 与前端菜单权限过滤，
 * 避免仅有登录态即可调用管理端接口。</p>
 */
@Slf4j
@Component
public class PermissionChecker {

    /** 超级管理员通配权限（sys_permission 中 `*:*:*`） */
    public static final String SUPER_PERMISSION = "*:*:*";

    /** 权限不足错误码（error-codes.properties COMMON005） */
    private static final String ERROR_CODE_NO_PERMISSION = "COMMON005";

    /**
     * 校验当前登录用户是否具备指定权限，无权限时抛出业务异常。
     *
     * @param permissionCode 权限编码，如 system:mail:view
     */
    public void require(String permissionCode) {
        if (!StpUtil.isLogin()) {
            // 未登录由 AuthInterceptor 拦截，此处不重复处理
            return;
        }
        if (hasPermission(permissionCode)) {
            return;
        }
        log.warn("权限校验失败: userId={}, permission={}", StpUtil.getLoginIdDefaultNull(), permissionCode);
        throw new BusinessException(ERROR_CODE_NO_PERMISSION);
    }

    /** 当前用户是否具备指定权限（含 `*:*:*` 通配） */
    public boolean hasPermission(String permissionCode) {
        List<String> permissions = StpUtil.getPermissionList();
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        if (permissions.contains(SUPER_PERMISSION)) {
            return true;
        }
        return StpUtil.hasPermission(permissionCode);
    }
}
