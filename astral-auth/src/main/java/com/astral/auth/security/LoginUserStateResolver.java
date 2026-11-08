package com.astral.auth.security;

import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 登录账号「是否仍然可用」的判定器（登录态兜底门禁）。
 *
 * <p><b>补的是哪条缝</b>：Sa-Token 的 {@code StpUtil.isLogin()} 只回答「这个 token 还有效吗」，
 * 不回答「它对应的账号还能不能用」。于是出现两类漏网：</p>
 * <ol>
 *   <li>{@code DELETE /api/v1/admin/system/user/{id}} 走 MyBatis-Plus 逻辑删除
 *       （{@code deleted=1}），并不吊销该用户的在线会话；</li>
 *   <li>生产排障时直接在库里改 {@code sys_user.status=0}（或 {@code deleted=1}）的账号，
 *       也没有任何代码路径会去踢它。</li>
 * </ol>
 * <p>而 {@code AuthInterceptor} 每请求都会滑动续期（剩余不足阈值即续满），
 * {@code sa-token.active-timeout = -1} 又关闭了闲置过期——即被删账号的会话
 * <b>不会自然过期</b>，可以无限期调用管理端接口，且权限仍按 {@code sys_user_role} 正常解析。
 * 这是一个「删除用户」语义与「会话失效」语义脱节的完整提权/持久化后门。</p>
 *
 * <p><b>为什么放在拦截器而不是每个写路径自觉调 kickout</b>：{@code kickout} 只能覆盖
 * 「本次代码改到的写路径」，遗漏必然复发（本次就是删除路径漏了）。放在认证链路里做兜底，
 * 任何来源的「账号被禁用/删除」都会在下一次请求时生效。</p>
 *
 * <p><b>性能</b>：按用户 ID 缓存 30 秒（{@link #CACHE_TTL_MILLIS}），稳态下每账号每 30 秒最多
 * 一次 {@code selectById}；缓存用 {@link ConcurrentHashMap} 自管 TTL，不引入额外依赖。
 * 超过 {@link #MAX_CACHE_ENTRIES} 时整体清空（账号基数受 {@code sys_user} 规模约束，
 * 正常远达不到；这里只是防内存无界）。</p>
 *
 * <p><b>故障取向</b>：查库异常时<b>放行</b>（fail-open）并记 warn。理由是本门禁是「删除/封禁后
 * 的兜底」，不是主认证；数据库抖动时把全体管理员锁在门外，损失大于收益。真正的删除/封禁
 * 路径本身仍会显式 {@code kickout}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginUserStateResolver {

    /** 缓存有效期：账号状态变更最多 30 秒内被兜底拦住（显式踢人仍即时生效） */
    static final long CACHE_TTL_MILLIS = 30_000L;

    /** 缓存条目上限（超出整体清空，防内存无界） */
    static final int MAX_CACHE_ENTRIES = 10_000;

    private final UserMapper userMapper;

    /** userId → 缓存的可用状态 */
    private final Map<Long, CachedState> cache = new ConcurrentHashMap<>();

    /** 缓存命中计数，仅用于日志（避免每次打日志） */
    private final AtomicLong evictions = new AtomicLong();

    /**
     * 判断账号当前是否仍然可用（未逻辑删除、未禁用）。
     *
     * <p><b>查库失败一律拒绝本次请求（fail-closed）</b>：本门禁是「删除/禁用后存量会话必须失效」的
     * 唯一兜底，如果查不到状态就放行，等于把兜底换成「数据库抖动期间任意旧会话继续可用」——
     * 而这正是被删账号要利用的窗口。抛 {@link IllegalStateException} 由全局异常处理器映射成
     * HTTP 503 + {@code COMMON001}，<b>不吊销、不踢出</b>任何会话：瞬时故障恢复后用户原会话仍然有效，
     * 不需要重新登录。</p>
     *
     * @param userId 用户 ID
     * @return 可用返回 {@code true}；账号不存在、已删除、已禁用返回 {@code false}
     * @throws IllegalStateException 账号状态查询失败（上层转 503，不据此踢人）
     */
    public boolean isActive(Long userId) {
        if (userId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        CachedState cached = cache.get(userId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.active();
        }
        boolean active = loadActive(userId);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
            evictions.incrementAndGet();
        }
        cache.put(userId, new CachedState(active, now + CACHE_TTL_MILLIS));
        return active;
    }

    /**
     * 清掉指定用户的缓存（封禁/删除/改状态后可调用，令兜底门禁立即生效）。
     *
     * @param userId 用户 ID
     */
    public void evict(Long userId) {
        if (userId != null) {
            cache.remove(userId);
        }
    }

    /** 清空全部缓存（测试与运维用） */
    public void evictAll() {
        cache.clear();
    }

    private boolean loadActive(Long userId) {
        try {
            // 只取 id/status 两列，不把整行（含 password 哈希、TOTP secret）读进内存：
            // 本门禁每 30 秒对活跃账号查一次库，没必要把凭据搬来搬去。
            // selectOne 由 MyBatis-Plus 逻辑删除插件自动追加 deleted = 0，
            // 因此「查不到」既覆盖物理不存在，也覆盖逻辑删除。
            User user = userMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers.<User>lambdaQuery()
                    .select(User::getId, User::getStatus)
                    .eq(User::getId, userId));
            if (user == null) {
                return false;
            }
            Integer status = user.getStatus();
            // status 为 null 视为可用（历史行），0 = 禁用
            return status == null || status != 0;
        } catch (RuntimeException e) {
            // fail-closed：状态查不到就拒绝本次请求（上层 503），绝不放行；
            // 但异常只向上抛、不在这里踢会话 —— 瞬时 DB 故障不该导致全员重新登录。
            // 日志只留安全摘要（用户 id + 异常类型），不打印 SQL / 数据源细节。
            log.error(
                    "[auth] 账号状态查询失败，拒绝本次请求（fail-closed）: userId={}, err={}",
                    userId,
                    e.getClass().getSimpleName());
            throw new IllegalStateException("账号状态校验暂不可用", e);
        }
    }

    /** 缓存的账号状态 */
    private record CachedState(boolean active, long expiresAt) {}
}
