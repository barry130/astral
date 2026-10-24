package com.astral.cms.common;

import cn.dev33.satoken.stp.StpUtil;

import java.util.List;

/**
 * CMS 内容可见性判定
 * <p>私密内容（status=private）只对「已登录且持有内容管理查看权限」的访客开放；
 * 匿名访客与普通 App 用户完全看不到（连 404 都不会暴露其存在）。</p>
 * <p>之所以复用管理端权限码 {@code admin:cms:view}，是为了不新增一套内容归属模型：
 * 本插件当前是单作者/小团队的站点，能进管理端的人就是内容的所有者。</p>
 */
public final class CmsVisibility {

    /** 私密内容的查看凭据（与 AdminCmsPostController 类级权限码一致） */
    public static final String VIEW_PERMISSION = "admin:cms:view";

    /** Sa-Token 超管通配权限 */
    private static final String SUPER_PERMISSION = "*:*:*";

    private CmsVisibility() {
    }

    /**
     * 当前请求是否可查看私密内容。
     *
     * <p>App 区接口（/api/v1/app/**）匿名放行，但浏览器若带上了管理端登录 Cookie，
     * Sa-Token 仍能读到登录态 —— 这正是「博客前台登录后可读私密内容」的实现基础。</p>
     */
    public static boolean canViewPrivate() {
        try {
            if (!StpUtil.isLogin()) {
                return false;
            }
            List<String> permissions = StpUtil.getPermissionList();
            return permissions.contains(SUPER_PERMISSION) || permissions.contains(VIEW_PERMISSION);
        } catch (Exception e) {
            // 匿名请求下 Sa-Token 可能抛未登录异常，按不可见处理
            return false;
        }
    }

    /**
     * 内容是否对当前访客可见。
     *
     * @param status 内容状态（draft/published/private/hidden）
     */
    public static boolean isVisible(String status) {
        if ("published".equals(status)) {
            return true;
        }
        if ("private".equals(status)) {
            return canViewPrivate();
        }
        return false;
    }
}
