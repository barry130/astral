package com.astral.ai.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.astral.ai.common.AiConstants;
import com.astral.ai.entity.AiToolEntity;
import org.junit.jupiter.api.Test;

/**
 * 计划期配置快照哈希（审计 2026-11-07 backend-ai-read-recovery R3）。
 *
 * <p>期望值全部由 .NET SHA-256 对拼接原文独立预计算锚定，不经生产实现重算，
 * 防止实现自证（同义反复）。</p>
 */
class AiToolServicePlanHashTest {

    /** SHA-256("http://example.test/a|HIGH|ADMIN_CONFIRM")，.NET 独立预计算锚。 */
    private static final String HASH_OF_CONFIG_A = "b8ea63cfe9535ed57454c6cd6912c55cb6ea76b044bbd943947d52accfbebb78";

    /** SHA-256("http://example.test/b|HIGH|ADMIN_CONFIRM")，.NET 独立预计算锚。 */
    private static final String HASH_OF_CONFIG_B = "63e30cd29cdde93d60a7bd82b50857110d1463d89975e09e1788ba63444e2341";

    /** SHA-256("|HIGH|ADMIN_CONFIRM")，.NET 独立预计算锚（executorConfig 为 null 的空串拼接）。 */
    private static final String HASH_OF_NULL_CONFIG =
            "c0cd78707cfed6cf7037f10d0aba720f786166611fa2aca0d68c4e66e8a15dbd";

    private static AiToolEntity toolWithConfig(String executorConfig) {
        AiToolEntity tool = new AiToolEntity();
        tool.setExecutorConfig(executorConfig);
        return tool;
    }

    @Test
    void hashesExecutorConfigRiskApprovalMaterial() {
        assertEquals(
                HASH_OF_CONFIG_A,
                AiToolService.planHash(
                        toolWithConfig("http://example.test/a"),
                        AiConstants.RiskLevel.HIGH,
                        AiConstants.ApprovalPolicy.ADMIN_CONFIRM));
    }

    @Test
    void distinguishesDifferentExecutorConfig() {
        assertEquals(
                HASH_OF_CONFIG_B,
                AiToolService.planHash(
                        toolWithConfig("http://example.test/b"),
                        AiConstants.RiskLevel.HIGH,
                        AiConstants.ApprovalPolicy.ADMIN_CONFIRM));
    }

    @Test
    void nullExecutorConfigNormalizesToEmptyMaterial() {
        assertEquals(
                HASH_OF_NULL_CONFIG,
                AiToolService.planHash(null, AiConstants.RiskLevel.HIGH, AiConstants.ApprovalPolicy.ADMIN_CONFIRM));
    }
}
