package com.astral.qt.controller;

import static org.junit.jupiter.api.Assertions.*;

import com.astral.common.annotation.RateLimit;
import com.astral.feedback.controller.AppFeedbackController;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * 覆盖限流声明存在性（D-FB-02 / D-QT-08 / D-QT-06）：
 * 曾被遗漏的匿名/用户写接口必须带 {@link RateLimit}。
 * 行为级验证（拦截器实际返回 429）需完整 MVC 上下文，另由集成环境回归。
 */
class RateLimitAnnotationTest {

    @Test
    void previouslyUnprotectedWriteEndpointsAreRateLimited() {
        assertTrue(hasRateLimit(QtAppController.class, "reportSource"), "source/report 应限流");
        assertTrue(hasRateLimit(QtAppUserController.class, "sendEmail"), "sendEmail 应限流");
        assertTrue(hasRateLimit(AppFeedbackController.class, "submit"), "feedback submit 应限流");
        assertTrue(hasRateLimit(AppFeedbackController.class, "reply"), "feedback reply 应限流");
    }

    private static boolean hasRateLimit(Class<?> type, String methodName) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(methodName) && method.isAnnotationPresent(RateLimit.class)) {
                return true;
            }
        }
        return false;
    }
}
