package com.astral.auth.security;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 用户权限/角色列表缓存。
 *
 * <p>历史实现对每次权限校验都实时查 4 张表（sys_user_role → sys_role_permission → sys_permission），
 * 一个后台页面动辄十几次校验 = 几十次查询。这里把「用户 → 权限码列表 / 角色码列表」缓存进
 * <b>Sa-Token 账号会话</b>（SaTokenDao 由 sa-token-redis-jackson 提供，即 Redis，
 * 多实例共享、跨实例一致）。</p>
 *
 * <p><b>一致性</b>：会话里同时记录写入时的<b>全局权限版本号</b>（{@link #VERSION_KEY}，
 * 存在 SaTokenDao 里）。角色/权限/用户角色关系发生任何变更时调用 {@link #bumpVersion()}，
 * 版本号变化会让所有用户下次读取时自动回源，无需遍历清理每个用户的缓存。</p>
 *
 * <p><b>降级</b>：Redis 不可用或会话取不到时直接回源查库，缓存失败绝不影响鉴权正确性
 * （异常只记录日志，不向上抛）。</p>
 */
@Slf4j
@Component
public class PermissionCache {

    /** 全局权限版本号在 SaTokenDao 中的键（值随手变更，只做相等比较） */
    public static final String VERSION_KEY = "astral:perm:version";

    /** 账号会话内：该用户权限码列表的写入版本 */
    private static final String SESSION_PERM_VERSION = "permVersion";
    /** 账号会话内：权限码列表 */
    private static final String SESSION_PERM_LIST = "permCodes";
    /** 账号会话内：角色码列表的写入版本 */
    private static final String SESSION_ROLE_VERSION = "roleVersion";
    /** 账号会话内：角色码列表 */
    private static final String SESSION_ROLE_LIST = "roleCodes";

    /** 账号会话内：写入时间戳（毫秒）后缀 */
    private static final String SESSION_AGE_SUFFIX = "At";

    /**
     * 缓存最长存活时间（毫秒）：即使某条写路径漏调 {@link #bumpVersion()}，
     * 脏数据最多存活这么久。默认 5 分钟。
     */
    private static final long MAX_AGE_MS = 300_000L;

    /** 读取用户权限码列表（命中缓存直接返回，否则用 loader 回源并写入缓存） */
    public List<String> getPermissions(Object loginId, Supplier<List<String>> loader) {
        return read(loginId, SESSION_PERM_LIST, SESSION_PERM_VERSION, loader);
    }

    /** 读取用户角色码列表（命中缓存直接返回，否则用 loader 回源并写入缓存） */
    public List<String> getRoles(Object loginId, Supplier<List<String>> loader) {
        return read(loginId, SESSION_ROLE_LIST, SESSION_ROLE_VERSION, loader);
    }

    /**
     * 递增全局权限版本：角色、权限、用户角色关系的任何写操作后调用。
     * <p>用随机值而非自增：无需读改写，天然避免并发写覆盖导致的版本号相同。</p>
     */
    public void bumpVersion() {
        try {
            SaTokenDao dao = dao();
            dao.set(VERSION_KEY, UUID.randomUUID().toString(), SaTokenDao.NEVER_EXPIRE);
            log.debug("权限版本已更新，全部用户缓存将在下次读取时回源");
        } catch (Exception e) {
            log.warn("权限版本更新失败（缓存将依赖会话过期自愈）: {}", e.getMessage());
        }
    }

    /** 当前全局权限版本（读取失败回退为固定值，此时等价于「不做版本判断」） */
    public String currentVersion() {
        try {
            String v = dao().get(VERSION_KEY);
            return v == null ? "0" : v;
        } catch (Exception e) {
            return "0";
        }
    }

    // ==================== 内部实现 ====================

    private List<String> read(Object loginId, String listKey, String versionKey, Supplier<List<String>> loader) {
        if (loginId == null) {
            return loader.get();
        }
        try {
            SaSession session = StpUtil.getSessionByLoginId(loginId, true);
            if (session == null) {
                return loader.get();
            }
            String version = currentVersion();
            Object cachedVersion = session.get(versionKey);
            Object cached = session.get(listKey);
            // 版本一致、未超龄且缓存结构可解析：直接命中
            if (version.equals(cachedVersion) && !expired(session, listKey) && cached instanceof List<?>) {
                return toStringList((List<?>) cached);
            }
            List<String> loaded = loader.get();
            session.set(listKey, loaded);
            session.set(versionKey, version);
            session.set(listKey + SESSION_AGE_SUFFIX, System.currentTimeMillis());
            return loaded;
        } catch (Exception e) {
            log.warn("权限缓存读写失败，回源查库: loginId={}, err={}", loginId, e.getMessage());
            return loader.get();
        }
    }

    private SaTokenDao dao() {
        return StpUtil.getStpLogic().getSaTokenDao();
    }

    /** 缓存是否超龄（时间戳缺失/不可解析视为超龄，强制回源） */
    private boolean expired(SaSession session, String listKey) {
        Object at = session.get(listKey + SESSION_AGE_SUFFIX);
        if (at == null) {
            return true;
        }
        try {
            return System.currentTimeMillis() - Long.parseLong(at.toString()) > MAX_AGE_MS;
        } catch (NumberFormatException e) {
            return true;
        }
    }

    private List<String> toStringList(List<?> raw) {
        List<String> list = new ArrayList<>(raw.size());
        for (Object o : raw) {
            if (o != null) {
                list.add(o.toString());
            }
        }
        return list;
    }
}
