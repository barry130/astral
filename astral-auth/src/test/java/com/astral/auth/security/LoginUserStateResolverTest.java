package com.astral.auth.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 账号状态兜底门禁的判定回归。
 *
 * <p>对应缺陷：{@code DELETE /api/v1/admin/system/user/{id}} 走 MyBatis-Plus 逻辑删除
 * （{@code deleted=1}），既不吊销该用户的在线会话，也没有任何状态校验兜底；而 token
 * 每请求滑动续期、{@code active-timeout=-1} 关闭闲置过期 —— 被删账号可以<b>无限期</b>
 * 继续调用管理端接口，权限照常按 {@code sys_user_role} 解析。</p>
 *
 * <p>这里锁住兜底门禁的四个判定：查不到（=逻辑删除/不存在）不可用、{@code status=0} 不可用、
 * {@code status=1} 与历史 {@code null} 可用；以及 30 秒短 TTL 缓存确实去重查询
 * （避免每请求一次 DB 查询把它变成性能问题）、{@code evict} 后立即回源。</p>
 */
class LoginUserStateResolverTest {

    private UserMapper userMapper;
    private LoginUserStateResolver resolver;

    @BeforeEach
    void setUp() {
        // 初始化真实 lambda 列元数据，不启动 Spring 或数据库，也不替换被测查询路径。
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "isolated-auth-test");
        assistant.setCurrentNamespace(UserMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, User.class);
        userMapper = mock(UserMapper.class);
        resolver = new LoginUserStateResolver(userMapper);
    }

    @Test
    @DisplayName("查不到用户（逻辑删除/不存在）→ 不可用")
    void missingUserIsInactive() {
        when(userMapper.selectOne(any())).thenReturn(null);
        assertFalse(resolver.isActive(1001L));
    }

    @Test
    @DisplayName("status=0（禁用）→ 不可用")
    void disabledUserIsInactive() {
        when(userMapper.selectOne(any())).thenReturn(userOf(1001L, 0));
        assertFalse(resolver.isActive(1001L));
    }

    @Test
    @DisplayName("status=1 → 可用")
    void enabledUserIsActive() {
        when(userMapper.selectOne(any())).thenReturn(userOf(1001L, 1));
        assertTrue(resolver.isActive(1001L));
    }

    @Test
    @DisplayName("status 为 null（历史行）→ 可用，不因缺值锁死老账号")
    void nullStatusIsActive() {
        when(userMapper.selectOne(any())).thenReturn(userOf(1001L, null));
        assertTrue(resolver.isActive(1001L));
    }

    @Test
    @DisplayName("30 秒内只查一次库；evict 后立即回源（封禁即时生效）")
    void shortTtlCacheDeduplicatesQueries() {
        when(userMapper.selectOne(any())).thenReturn(userOf(1001L, 1));

        assertTrue(resolver.isActive(1001L));
        assertTrue(resolver.isActive(1001L));
        assertTrue(resolver.isActive(1001L));
        verify(userMapper, times(1)).selectOne(any());

        resolver.evict(1001L);
        assertTrue(resolver.isActive(1001L));
        verify(userMapper, times(2)).selectOne(any());
    }

    @Test
    @DisplayName("userId 为 null → 不可用（不接受空身份）")
    void nullUserIdIsInactive() {
        assertFalse(resolver.isActive(null));
    }

    @Test
    @DisplayName("查询异常 → fail-closed：抛 IllegalStateException（上层 503），不缓存、不静默放行")
    void queryFailureIsFailClosed() {
        when(userMapper.selectOne(any())).thenThrow(new IllegalStateException("db down"));

        assertThrows(IllegalStateException.class, () -> resolver.isActive(1001L));
        // 失败不能写进缓存：故障恢复后必须重新回源，不能把「异常」或「不可用」缓存 30 秒
        assertThrows(IllegalStateException.class, () -> resolver.isActive(1001L));
        verify(userMapper, times(2)).selectOne(any());
    }

    /** 构造只填了判定所需两列的用户（门禁只读 id/status，不加载密码） */
    private static User userOf(Long id, Integer status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        return user;
    }
}
