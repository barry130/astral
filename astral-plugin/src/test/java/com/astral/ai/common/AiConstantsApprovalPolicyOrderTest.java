package com.astral.ai.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 确认策略 order() 的单调契约。
 *
 * <p>期望值锚定 {@code ApprovalPolicy.all()} 的声明序（NONE、USER_CONFIRM、ADMIN_CONFIRM）
 * 与审计契约原文（backend-ai-read-recovery.md R5：NONE&lt;USER_CONFIRM&lt;ADMIN_CONFIRM），
 * 非法值按 {@code RiskLevel.order} 同族约定返回 0（永不高于任何合法 floor，fail-closed）。</p>
 */
class AiConstantsApprovalPolicyOrderTest {

    @Test
    void orderFollowsTheDeclaredStrengthLadder() {
        assertTrue(
                AiConstants.ApprovalPolicy.order(AiConstants.ApprovalPolicy.NONE)
                        < AiConstants.ApprovalPolicy.order(AiConstants.ApprovalPolicy.USER_CONFIRM),
                "NONE must be weaker than USER_CONFIRM");
        assertTrue(
                AiConstants.ApprovalPolicy.order(AiConstants.ApprovalPolicy.USER_CONFIRM)
                        < AiConstants.ApprovalPolicy.order(AiConstants.ApprovalPolicy.ADMIN_CONFIRM),
                "USER_CONFIRM must be weaker than ADMIN_CONFIRM");
    }

    @Test
    void illegalValuesNeverOutrankAnyLegalPolicy() {
        assertEquals(0, AiConstants.ApprovalPolicy.order("WHENEVER"));
        assertEquals(0, AiConstants.ApprovalPolicy.order(null));
        assertTrue(AiConstants.ApprovalPolicy.order("WHENEVER")
                < AiConstants.ApprovalPolicy.order(AiConstants.ApprovalPolicy.NONE));
    }
}
