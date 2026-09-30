package com.astral.common.constant;

/**
 * 权限类型（sys_permission.type）。
 *
 * <p>1-3 为前端菜单树类型（参与导航渲染）；4-5 为后端权限类型，<b>不</b>参与菜单渲染：</p>
 * <ul>
 *   <li>{@link #API}：接口权限——决定某类后端接口是否可达；</li>
 *   <li>{@link #DATA}：数据权限——决定同一接口的结果可见范围（结果级权限）。</li>
 * </ul>
 *
 * <p>类型值同时登记在数据字典 {@code permission_type}，前端文案以字典为准。</p>
 */
public final class PermissionType {

    /** 目录（前端菜单树节点） */
    public static final int DIR = 1;

    /** 菜单（前端路由） */
    public static final int MENU = 2;

    /** 按钮（前端页面内操作） */
    public static final int BUTTON = 3;

    /** 接口（后端 API 可达性） */
    public static final int API = 4;

    /** 数据（后端结果可见范围 / 结果级权限） */
    public static final int DATA = 5;

    private PermissionType() {
    }

    /** 是否为前端菜单树类型（菜单管理/导航渲染用） */
    public static boolean isMenuType(Integer type) {
        return type != null && type >= DIR && type <= BUTTON;
    }
}
