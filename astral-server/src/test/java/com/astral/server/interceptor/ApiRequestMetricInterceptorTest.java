package com.astral.server.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.astral.monitor.service.ApiMetricCollector;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.HandlerMapping;

class ApiRequestMetricInterceptorTest {
    @Test
    void parameterValuesDoNotBecomeMetricRoutes() {
        ApiMetricCollector collector = new ApiMetricCollector();
        ApiRequestMetricInterceptor interceptor = new ApiRequestMetricInterceptor(collector);
        ReflectionTestUtils.setField(interceptor, "clientUtHeader", "X-Client-UT");
        ReflectionTestUtils.setField(interceptor, "clientVersionHeader", "X-App-Version");
        ReflectionTestUtils.setField(interceptor, "legacyPlatformHeader", "X-Platform");
        for (int id = 1; id <= 20; id++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/items/" + id);
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/items/{id}");
            MockHttpServletResponse response = new MockHttpServletResponse();
            interceptor.preHandle(request, response, null);
            interceptor.afterCompletion(request, response, null, null);
        }

        var metrics = collector.drain();
        assertEquals(1, metrics.size());
        assertEquals("/api/items/{id}", metrics.keySet().iterator().next().getUri());
        assertEquals(20, metrics.values().iterator().next().getCallCount());
    }
}
