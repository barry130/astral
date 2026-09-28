package com.astral.auth.security;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 登录用户「用户类型」解析器。
 *
 * <p><b>为什么需要它</b>：宿主管理端与轻听 App 端<b>共用同一套 Sa-Token 命名空间</b>
 * （两端都走 {@code StpUtil.login(userId)}），因此 {@code StpUtil.isLogin()} 无法区分
 * 「后台管理员」与「App 普通用户」。App 端注册接口 {@code /api/v1/app/user/register}
 * 是免认证的，任何人注册后都能通过登录态校验——若管理端只校验「是否登录」，
 * 就等于把 {@code /api/v1/admin/**} 对外开放。</p>
 *
 * <p>本类通过 {@code sys_user.user_type}（{@code ADMIN} / {@code APP}）区分身份。
 * 该列在 V20260914001 迁移中定义为 {@code NOT NULL DEFAULT 'ADMIN'}，
 * 因此历史管理员账号天然是 {@code ADMIN}，不会因引入校验而被锁在门外。</p>
 *
 * <p><b>性能</b>：优先读 Sa-Token 会话缓存（登录时写入），只有缓存缺失（例如
 * 升级前签发的旧 token）才回查数据库并回填，稳态下不产生额外查询。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginUserTypeResolver {

    /** 管理端用户 */
    public static final String TYPE_ADMIN = "ADMIN";

    /** App（轻听插件）用户 */
    public static final String TYPE_APP = "APP";

    /** 会话中缓存 user_type 的键名 */
    public static final String SESSION_KEY_USER_TYPE = "userType";

    private final UserMapper userMapper;

    /**
     * 解析当前登录用户的 user_type。
     *
     * @return {@code ADMIN} / {@code APP}；未登录或用户不存在返回 {@code null}
     */
    public String currentUserType() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            return null;
        }
        return resolve(loginId);
    }

    /**
     * 解析指定登录用户的 user_type（含会话缓存回填）。
     *
     * @param loginId Sa-Token 的 loginId
     * @return {@code ADMIN} / {@code APP}；无法解析返回 {@code null}
     */
    public String resolve(Object loginId) {
        if (loginId == null) {
            return null;
        }

        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            Object cached = session.get(SESSION_KEY_USER_TYPE);
            if (cached != null) {
                return cached.toString();
            }
        }

        Long userId;
        try {
            userId = Long.valueOf(loginId.toString());
        } catch (NumberFormatException e) {
            log.warn("无法解析 loginId 为 Long，跳过 user_type 查询: loginId={}", loginId);
            return null;
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        String userType = user.getUserType();

        // 回填会话缓存：升级前签发的旧 token 只有第一次会查库
        if (session != null && userType != null) {
            session.set(SESSION_KEY_USER_TYPE, userType);
        }
        return userType;
    }

    /**
     * 把 user_type 写入当前会话（登录成功后调用）。
     *
     * @param userType 用户类型
     */
    public void cacheCurrentUserType(String userType) {
        if (userType == null) {
            return;
        }
        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            session.set(SESSION_KEY_USER_TYPE, userType);
        }
    }

    /**
     * 清除当前会话中的 user_type 缓存。
     *
     * <p>当管理端修改了某用户的 user_type（如把 ADMIN 降级为 APP）时，
     * 应调用本方法使其下次请求重新解析，避免缓存导致权限变更延迟生效。</p>
     */
    public void evictCurrentCache() {
        SaSession session = StpUtil.getSession(false);
        if (session != null) {
            session.delete(SESSION_KEY_USER_TYPE);
        }
    }
}
