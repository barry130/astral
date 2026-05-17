package com.astral.server.interceptor;

import com.astral.server.service.ApiStatisticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Slf4j
@Component
@RequiredArgsConstructor
public class ApiStatisticsInterceptor implements HandlerInterceptor {

    private final ApiStatisticsService apiStatisticsService;
    private static final String START_TIME_ATTRIBUTE = "apiStartTime";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        request.setAttribute(START_TIME_ATTRIBUTE, System.currentTimeMillis());
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {
        Long startTime = (Long) request.getAttribute(START_TIME_ATTRIBUTE);
        if (startTime != null) {
            long executeTime = System.currentTimeMillis() - startTime;
            String apiPath = request.getRequestURI();
            String apiMethod = request.getMethod();
            int status = response.getStatus();
            boolean success = status >= 200 && status < 300;
            
            apiStatisticsService.recordApiCall(apiPath, apiMethod, success, executeTime);
        }
    }
}
