package com.astral.qt.service;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 邮箱验证码校验脚本的结构校验。
 *
 * <p>没有真实 Redis 也要守住这条：验证码校验必须是**单条原子脚本**。
 * 拆成「GET → Java 比较 → INCR」时，并发请求全部挤在「还没人记失败」的空窗里，
 * 5 次封顶等于不存在 —— 这正是本次修复的缺陷，回归时必须能自动发现
 * （一旦有人把 {@code execute(VERIFY_CODE_SCRIPT,...)} 改回多次往返，
 * 脚本会被删除或改名，本测试即失败）。</p>
 */
class QtEmailCodeVerifyScriptTest {

    @Test
    void verifyCodeScriptIsSingleAtomicLua() throws Exception {
        Field field = QtUserService.class.getDeclaredField("VERIFY_CODE_SCRIPT");
        field.setAccessible(true);
        Object value = field.get(null);
        assertInstanceOf(DefaultRedisScript.class, value, "验证码校验必须走 Lua 脚本");

        String lua = ((DefaultRedisScript<?>) value).getScriptAsString();
        assertNotNull(lua);
        // 失败计数与作废都发生在脚本内（单次往返内完成）
        assertTrue(lua.contains("redis.call('GET', KEYS[1])"), "脚本要先读验证码: " + lua);
        assertTrue(lua.contains("redis.call('INCR', KEYS[2])"), "失败必须原子自增: " + lua);
        assertTrue(lua.contains("redis.call('DEL', KEYS[1])"), "超限必须作废验证码: " + lua);
        // 值相等必须用字符串比较，不能用数值比较（验证码可能有前导 0）
        assertTrue(lua.contains("stored == ARGV[1]"), "比较必须按字符串: " + lua);
        assertTrue(lua.contains("return 1") && lua.contains("return -2"),
                "返回码语义：1=通过 / -2=不存在: " + lua);
    }

    @Test
    void verifyCodeScriptDeclaresResultType() throws Exception {
        Field field = QtUserService.class.getDeclaredField("VERIFY_CODE_SCRIPT");
        field.setAccessible(true);
        DefaultRedisScript<?> script = (DefaultRedisScript<?>) field.get(null);
        // 返回类型必须是 Long，否则 execute() 会得到 null 之外的意外类型
        assertEquals(Long.class, script.getResultType());
    }
}
