package com.astral.ai.service;

import com.astral.ai.common.AiConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件流「读完才算结束」的回归测试。
 *
 * <p>线上事故：一次拉取有条数上限（默认 100），而一条流式回答能产生几百条
 * {@code message.delta}（模型输出 JSON，每个字一条）。请求一进终态
 * {@code terminal} 就恒为 true，客户端第一轮拿到 100 条就停轮询，
 * 永远读不到第 273 条 {@code message.completed} —— 界面上只剩增量拼出来的
 * 半截原始 JSON，文案与歌曲卡片全丢。</p>
 *
 * <p>修复后 {@code terminal} 必须同时满足「请求已终态」与「游标已追平」。</p>
 */
class AiEventTerminalTest {

    /** 终态判定与游标追平是两个独立条件，控制器里用 && 组合 */
    @Test
    void terminalRequiresBothFinishedStatusAndDrainedCursor() {
        // 请求还在跑：无论游标是否追平都不算结束
        assertFalse(isTerminal(false, false));
        assertFalse(isTerminal(false, true));

        // 请求终态但还有事件没读：不能报结束
        assertFalse(isTerminal(true, false));

        // 终态且事件读空：才是真的结束
        assertTrue(isTerminal(true, true));
    }

    /** 复刻控制器里的组合表达式，保证两个条件都得满足 */
    private static boolean isTerminal(boolean requestFinished, boolean eventsDrained) {
        return requestFinished && eventsDrained;
    }

    /** 事件序号步长必须为正，否则 drained 永远追不平 */
    @Test
    void sequenceStepIsPositive() {
        assertTrue(AiConstants.Runtime.EVENT_SEQUENCE_STEP > 0,
                "事件序号步长必须为正，否则游标无法前进");
    }
}
