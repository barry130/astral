package com.astral.ai.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R4 工具权限码解析契约：解析器必须接受代码库真实存在的权限编码形态。
 *
 * <p>期望值锚定审计契约原文（docs/audit/2026-11-07/backend-ai-read-recovery.md R4）与
 * AGENTS 权限编码规范：真实编码包括超管 {@code *:*:*}、层级通配 {@code admin:system:user:*}、
 * 3 段短码 {@code admin:qt:admin} / {@code admin:demo:view}、范围段 {@code user:qt:update:channel:beta}；
 * 非法输入仍 fail-closed 抛 AI008。</p>
 */
class AiJsonPermissionCsvTest {

    @Test
    void acceptsRealWorldPermissionCodesFromTheToolContract() {
        List<String> codes = AiJson.splitPermissionCsv(
                "admin:qt:admin,admin:demo:view,*:*:*,admin:system:user:*,user:qt:update:channel:beta");

        assertEquals(
                List.of(
                        "admin:qt:admin",
                        "admin:demo:view",
                        "*:*:*",
                        "admin:system:user:*",
                        "user:qt:update:channel:beta"),
                codes);
    }

    @Test
    void acceptsDomainLevelShortCodes() {
        assertEquals(List.of("admin:qt"), AiJson.splitPermissionCsv("admin:qt"));
    }

    @Test
    void stillFailsClosedOnMalformedEntries() {
        assertThrows(AiException.class, () -> AiJson.splitPermissionCsv("not a permission code"));
        assertThrows(AiException.class, () -> AiJson.splitPermissionCsv(",,"));
        assertThrows(AiException.class, () -> AiJson.splitPermissionCsv("admin"));
        assertThrows(AiException.class, () -> AiJson.splitPermissionCsv("admin::view"));
        assertThrows(AiException.class, () -> AiJson.splitPermissionCsv("qt_admin"));
    }

    @Test
    void blankInputYieldsNoRequirements() {
        assertTrue(AiJson.splitPermissionCsv(null).isEmpty());
        assertTrue(AiJson.splitPermissionCsv("").isEmpty());
        assertTrue(AiJson.splitPermissionCsv("   ").isEmpty());
    }
}
