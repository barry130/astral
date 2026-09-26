package com.astral.auth.security;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.common.exception.BusinessException;
import jakarta.annotation.Resource;
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

    /** 轻听管理权限编码（此前已存在；测试版投放人群：qt_admin 与 qt_tester 均放行） */
    public static final String QT_ADMIN_PERMISSION = "qt_admin";

    /** 轻听测试权限编码（测试版渠道的最小投放权限，由 V20260926001 迁移登记） */
    public static final String QT_TESTER_PERMISSION = "qt_tester";

    /** 权限不足错误码（error-codes.properties COMMON005） */
    private static final String ERROR_CODE_NO_PERMISSION = "COMMON005";

    /** Sa-Token 权限数据源 SPI（StpInterfaceImpl），用于按 loginId 显式拉取权限列表 */
    @Resource
    private StpInterface stpInterface;

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

    /**
     * 判断指定登录用户是否具备任一权限编码（含 `*:*:*` 通配）。
     * <p>供免认证接口按请求携带的 token 显式判定人群（如测试版投放），
     * 不依赖当前线程的登录上下文；loginId 为 null 返回 false。</p>
     *
     * @param loginId         登录用户 ID（可为 null）
     * @param permissionCodes 权限编码列表，命中任一即返回 true
     * @return 具备任一权限返回 true
     */
    public boolean hasAnyPermissionByLoginId(Object loginId, String... permissionCodes) {
        if (loginId == null) {
            return false;
        }
        List<String> permissions = stpInterface.getPermissionList(loginId, StpUtil.TYPE);
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }
        if (permissions.contains(SUPER_PERMISSION)) {
            return true;
        }
        for (String code : permissionCodes) {
            if (permissions.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按会话 token 判断对应登录用户是否具备任一权限编码（含 `*:*:*` 通配）。
     * <p>token 为空或无效（无法反查登录用户）返回 false，即视为非测试人群。</p>
     *
     * @param token           会话 token（如请求头 satoken，可为 null）
     * @param permissionCodes 权限编码列表，命中任一即返回 true
     * @return 具备任一权限返回 true
     */
    public boolean hasAnyPermissionByToken(String token, String... permissionCodes) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return hasAnyPermissionByLoginId(StpUtil.getLoginIdByToken(token), permissionCodes);
    }
}
