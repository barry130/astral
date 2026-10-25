package com.astral.server;

import com.astral.common.util.ClientHeaders;
import com.astral.monitor.service.StatIngestService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 匿名上报的「维度归一 + 时间戳裁剪」守卫（回归保护）。
 *
 * <p>{@code appVersion} 只截断不校验形态时，每次请求换个随机串就能在
 * {@code stat_metric_hourly} / {@code stat_error_log} 里持续制造新分组（保留 180/90 天）；
 * {@code ts} 不做区间裁剪时，匿名调用方能把数据写进任意历史/未来小时的桶，
 * 报表可信度归零。放在 astral-server 模块是因为它同时依赖 astral-common 与 astral-monitor。</p>
 */
class StatIngestPrivacyTest {

    @Test
    void normalizeVersionKeepsOnlyVersionShapedValues() {
        assertEquals("1.2.3", ClientHeaders.normalizeVersion("1.2.3"));
        // 分隔符不能连用（1.2.3-beta_1 里 "-beta_1" 是两段，合法；"--x" 会被拒）
        assertEquals("1.2.3-beta", ClientHeaders.normalizeVersion(" 1.2.3-beta "));
        assertTrue(ClientHeaders.normalizeVersion("x y z").isEmpty());
        assertTrue(ClientHeaders.normalizeVersion("<script>").isEmpty());
        // 超长按列宽截断（MAX_VERSION=32），且截断后仍是合法版本形态
        assertEquals(32, ClientHeaders.normalizeVersion("a".repeat(40)).length());
    }

    @Test
    void platformIsWhitelisted() {
        assertEquals("app-android", ClientHeaders.normalizeUt("app-android"));
        assertTrue(ClientHeaders.normalizeUt("app-planet").isEmpty());
        assertTrue(ClientHeaders.normalizeUt(null).isEmpty());
    }

    @Test
    void eventTimestampIsClampedToReasonableWindow() throws Exception {
        StatIngestService service = new StatIngestService(null, null, null, null);
        Method toLocalDateTime = StatIngestService.class.getDeclaredMethod("toLocalDateTime", Long.class);
        toLocalDateTime.setAccessible(true);

        long now = System.currentTimeMillis();
        LocalDateTime nowBucket = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        LocalDateTime fresh = (LocalDateTime) toLocalDateTime.invoke(service, now);
        LocalDateTime skewed = (LocalDateTime) toLocalDateTime.invoke(service, now - 365L * 24 * 3600 * 1000);
        LocalDateTime future = (LocalDateTime) toLocalDateTime.invoke(service, now + 365L * 24 * 3600 * 1000);

        assertTrue(Math.abs(Duration.between(fresh, nowBucket).toMinutes()) <= 5,
                "当前时间戳应原样使用: " + fresh);
        assertTrue(Math.abs(Duration.between(skewed, nowBucket).toDays()) < 1,
                "远古时间戳应被裁剪到当前时间，实际: " + skewed);
        assertTrue(Math.abs(Duration.between(future, nowBucket).toDays()) < 1,
                "未来时间戳应被裁剪到当前时间，实际: " + future);
    }

    @Test
    void nullTimestampFallsBackToNow() throws Exception {
        StatIngestService service = new StatIngestService(null, null, null, null);
        Method toLocalDateTime = StatIngestService.class.getDeclaredMethod("toLocalDateTime", Long.class);
        toLocalDateTime.setAccessible(true);
        assertNotNull(toLocalDateTime.invoke(service, (Long) null));
        assertNotNull(toLocalDateTime.invoke(service, 0L));
    }
}
