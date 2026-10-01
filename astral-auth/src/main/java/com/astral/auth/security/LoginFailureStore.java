package com.astral.auth.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败计数存储（Redis 为主，内存兜底）
 *
 * <p>此前失败计数存在 {@code RsaKeyManager} 的 JVM 内存 Map 里，重启即清零、
 * 多实例也不共享——攻击者只要等一次发布就能重置计数。现改为 Redis 计数：</p>
 * <ul>
 *   <li>{@code astral:login:fail:{username}} 失败次数，15 分钟滑动窗口（首键写时设 TTL）</li>
 *   <li>{@code astral:login:lock:{username}} 锁定标记，TTL = 锁定时长</li>
 * </ul>
 *
 * <p>Redis 异常时<b>降级回内存 Map</b>（语义与旧实现一致）：登录链路不能因为
 * 限流存储故障而整体不可用；内存态在 Redis 恢复前的窗口内仍提供单机保护。</p>
 */
@Slf4j
@Component
public class LoginFailureStore {

    static final int MAX_FAILURES = 5;
    static final long LOCK_DURATION_MS = 15 * 60 * 1000L;
    static final long FAILURE_WINDOW_SEC = 15 * 60L;

    private static final String FAIL_KEY = "astral:login:fail:";
    private static final String LOCK_KEY = "astral:login:lock:";

    private final ConcurrentHashMap<String, FailureRecord> memoryFallback = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private static class FailureRecord {
        int count;
        long lastFailureTime;
        long lockUntil;
    }

    /** 记录一次登录失败；满 {@value MAX_FAILURES} 次落锁定标记 */
    public void recordFailure(String username) {
        String key = FAIL_KEY + username;
        try {
            if (redisTemplate != null) {
                Long count = redisTemplate.opsForValue().increment(key);
                if (count != null && count == 1) {
                    redisTemplate.expire(key, Duration.ofSeconds(FAILURE_WINDOW_SEC));
                }
                if (count != null && count >= MAX_FAILURES) {
                    redisTemplate.opsForValue().set(LOCK_KEY + username, "1",
                            Duration.ofMillis(LOCK_DURATION_MS));
                }
                return;
            }
        } catch (Exception e) {
            log.warn("[login-limit] Redis 计数失败，降级内存态: {}", e.getMessage());
        }
        recordInMemory(username);
    }

    /** 登录成功后清除计数与锁定 */
    public void reset(String username) {
        try {
            if (redisTemplate != null) {
                redisTemplate.delete(FAIL_KEY + username);
                redisTemplate.delete(LOCK_KEY + username);
                return;
            }
        } catch (Exception e) {
            log.warn("[login-limit] Redis 清除失败，降级内存态: {}", e.getMessage());
        }
        memoryFallback.remove(username);
    }

    /** 账号是否处于锁定窗口内 */
    public boolean isLocked(String username) {
        try {
            if (redisTemplate != null) {
                return Boolean.TRUE.equals(redisTemplate.hasKey(LOCK_KEY + username));
            }
        } catch (Exception e) {
            log.warn("[login-limit] Redis 读取失败，降级内存态: {}", e.getMessage());
        }
        return isLockedInMemory(username);
    }

    /** 剩余可尝试次数（锁定中返回 0） */
    public int remainingFailures(String username) {
        try {
            if (redisTemplate != null) {
                if (Boolean.TRUE.equals(redisTemplate.hasKey(LOCK_KEY + username))) {
                    return 0;
                }
                String count = redisTemplate.opsForValue().get(FAIL_KEY + username);
                if (count == null) {
                    return MAX_FAILURES;
                }
                return Math.max(0, MAX_FAILURES - Integer.parseInt(count));
            }
        } catch (Exception e) {
            log.warn("[login-limit] Redis 读取失败，降级内存态: {}", e.getMessage());
        }
        return remainingInMemory(username);
    }

    // ---------- 内存兜底（语义与旧 RsaKeyManager 内 Map 一致） ----------

    private void recordInMemory(String username) {
        memoryFallback.compute(username, (key, record) -> {
            long now = System.currentTimeMillis();
            if (record == null) {
                record = new FailureRecord();
            }
            if (now - record.lastFailureTime > FAILURE_WINDOW_SEC * 1000) {
                record.count = 0;
            }
            record.count++;
            record.lastFailureTime = now;
            if (record.count >= MAX_FAILURES) {
                record.lockUntil = now + LOCK_DURATION_MS;
            }
            return record;
        });
    }

    private boolean isLockedInMemory(String username) {
        FailureRecord record = memoryFallback.get(username);
        if (record == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (record.lockUntil > 0 && now < record.lockUntil) {
            return true;
        }
        if (now - record.lastFailureTime > FAILURE_WINDOW_SEC * 1000) {
            memoryFallback.remove(username);
            return false;
        }
        return false;
    }

    private int remainingInMemory(String username) {
        FailureRecord record = memoryFallback.get(username);
        if (record == null) {
            return MAX_FAILURES;
        }
        long now = System.currentTimeMillis();
        if (record.lockUntil > 0 && now < record.lockUntil) {
            return 0;
        }
        if (now - record.lastFailureTime > FAILURE_WINDOW_SEC * 1000) {
            memoryFallback.remove(username);
            return MAX_FAILURES;
        }
        return Math.max(0, MAX_FAILURES - record.count);
    }
}
