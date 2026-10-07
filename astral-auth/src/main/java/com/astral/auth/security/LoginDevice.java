package com.astral.auth.security;

/**
 * Sa-Token 登录设备标识（{@code StpUtil.login(id, new SaLoginModel().setDevice(...))} 的 device 参数）。
 *
 * <p>{@code is-share: false} 后每次登录产生独立 token，device 维度承担两件事：</p>
 * <ul>
 *   <li>「账号 × 端」会话隔离：一端登出 / 被踢 / 过期不影响其他端；</li>
 *   <li>在线会话管理（Token 管理页）按端展示与单独吊销。</li>
 * </ul>
 *
 * <p>取值刻意与 {@code sys_user.user_type} 的 ADMIN/APP 对齐，便于一眼对应，
 * 但语义是「登录端」而非「账号类型」——同一个 ADMIN 账号将来若开放登录 App，
 * 应以实际登录端为准选 device，不要从 user_type 推导。</p>
 */
public final class LoginDevice {

    /** 管理端（astral-front 后台） */
    public static final String ADMIN = "ADMIN";

    /** 轻听 App 客户端（qt-uniappx） */
    public static final String APP = "APP";

    private LoginDevice() {
    }
}
