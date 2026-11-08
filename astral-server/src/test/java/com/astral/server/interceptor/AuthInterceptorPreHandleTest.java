package com.astral.server.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.security.AuthCookieWriter;
import com.astral.auth.security.LoginUserStateResolver;
import com.astral.auth.security.LoginUserTypeResolver;
import com.astral.auth.service.UserLoginMarker;
import com.astral.common.result.Result;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link AuthInterceptor#preHandle} 的<b>实际执行路径</b>回归（不是分类 helper 的单元测试）。
 *
 * <p>用 {@code Mockito.mockStatic(StpUtil.class)} 把 Sa-Token 静态入口打成可控桩，
 * 只 mock「外部协作者」（类型解析/状态解析/日志标记/Cookie 写入/ObjectMapper），
 * 拦截器本身跑真实代码，因此能证明三件必须成立的事：</p>
 *
 * <ol>
 *   <li><b>账号状态查询失败 ⇒ fail-closed</b>：HTTP 503 + {@code body.code=503} +
 *       {@code errorCode=COMMON001}，且<b>不</b> {@code logout}/{@code logoutByTokenValue}/{@code kickout}；</li>
 *   <li><b>账号已删除/禁用 ⇒ 踢会话且 401</b>：管理端区 {@code logout()} 恰好一次，
 *       App 用户区按已知 token {@code logoutByTokenValue(token)} 恰好一次；</li>
 *   <li><b>管理端身份门禁真实生效</b>：{@code user_type=APP} 的已登录用户访问
 *       {@code /api/v1/admin/**} 得 403 + {@code COMMON005}，{@code user_type=ADMIN} 放行。</li>
 * </ol>
 */
class AuthInterceptorPreHandleTest {

    private static final String RAW_TOKEN = "raw-session-token-for-test";

    private static final Long USER_ID = 1001L;

    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final LoginUserTypeResolver userTypeResolver = mock(LoginUserTypeResolver.class);
    private final UserLoginMarker userLoginMarker = mock(UserLoginMarker.class);
    private final AuthCookieWriter authCookieWriter = mock(AuthCookieWriter.class);
    private final LoginUserStateResolver stateResolver = mock(LoginUserStateResolver.class);

    private final List<Object> writtenBodies = new ArrayList<>();

    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        // 只截获「写进响应体的 Result」，不引入 Jackson 序列化细节
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            writtenBodies.add(invocation.getArgument(0));
            return "{}";
        });
        interceptor =
                new AuthInterceptor(objectMapper, userTypeResolver, userLoginMarker, authCookieWriter, stateResolver);
        setField(interceptor, "adminRequiredUserType", LoginUserTypeResolver.TYPE_ADMIN);
        // 续期阈值置 0：本组用例只关心认证/门禁判定，避免续期分支产生额外静态调用
        setField(interceptor, "renewThreshold", 0L);
        setField(interceptor, "tokenTimeout", 259_200L);
    }

    @Test
    @DisplayName("账号状态查询异常 → HTTP 503 + code 503 + COMMON001，且不踢会话（fail-closed）")
    void stateLookupFailureIsFailClosedAndKeepsSession() throws Exception {
        MockHttpServletRequest request = request("/api/v1/admin/system/user/page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(USER_ID);
            when(stateResolver.isActive(USER_ID)).thenThrow(new IllegalStateException("db down"));

            assertFalse(interceptor.preHandle(request, response, new Object()), "状态查询失败必须拒绝请求");

            assertEquals(503, response.getStatus(), "服务不可用必须是传输层 503");
            Result<?> body = onlyWrittenBody();
            assertEquals(503, body.getCode());
            assertEquals("COMMON001", body.getErrorCode());

            stp.verify(StpUtil::logout, never());
            stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
            stp.verify(() -> StpUtil.kickout(any()), never());
            verify(stateResolver, times(1)).isActive(USER_ID);
        }
    }

    @Test
    @DisplayName("管理端账号已禁用/已删除 → 200 + code 401 + AUTH001，logout 恰好一次")
    void inactiveAdminAccountKicksExactlyOnce() throws Exception {
        MockHttpServletRequest request = request("/api/v1/admin/system/user/page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(USER_ID);
            when(stateResolver.isActive(USER_ID)).thenReturn(false);

            assertFalse(interceptor.preHandle(request, response, new Object()));

            assertEquals(200, response.getStatus(), "未登录/失效仍是 HTTP 200 + 业务码 401");
            Result<?> body = onlyWrittenBody();
            assertEquals(401, body.getCode());
            assertEquals("AUTH001", body.getErrorCode());

            stp.verify(StpUtil::logout, times(1));
            stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
            // 禁用/删除不是权限变更：不应触发全端 kickout（那会连带踢掉其它端的合法会话）
            stp.verify(() -> StpUtil.kickout(any()), never());
        }
    }

    @Test
    @DisplayName("App 用户区账号已禁用 → 按已知 token 精确登出，且不调用 logout()")
    void inactiveAppUserLogsOutByExactToken() throws Exception {
        MockHttpServletRequest request = request("/api/v1/app/user/profile");
        request.addHeader("satoken", RAW_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken(RAW_TOKEN)).thenReturn(USER_ID);
            when(stateResolver.isActive(USER_ID)).thenReturn(false);

            assertFalse(interceptor.preHandle(request, response, new Object()));

            assertEquals(200, response.getStatus());
            Result<?> body = onlyWrittenBody();
            assertEquals(401, body.getCode());
            assertEquals("AUTH001", body.getErrorCode());

            stp.verify(() -> StpUtil.logoutByTokenValue(RAW_TOKEN), times(1));
            stp.verify(StpUtil::logout, never());
        }
    }

    @Test
    @DisplayName("App 用户区账号状态查询异常 → 503 且不按 token 登出")
    void appUserStateLookupFailureNeverLogsOut() throws Exception {
        MockHttpServletRequest request = request("/api/v1/app/user/profile");
        request.addHeader("satoken", RAW_TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(() -> StpUtil.getLoginIdByToken(RAW_TOKEN)).thenReturn(USER_ID);
            when(stateResolver.isActive(USER_ID)).thenThrow(new IllegalStateException("db down"));

            assertFalse(interceptor.preHandle(request, response, new Object()));

            assertEquals(503, response.getStatus());
            assertEquals(503, onlyWrittenBody().getCode());
            stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
            stp.verify(StpUtil::logout, never());
        }
    }

    @Test
    @DisplayName("管理端身份门禁：user_type=APP 的已登录用户访问 /admin/** → 403 + COMMON005")
    void appUserIsRejectedOnAdminPath() throws Exception {
        MockHttpServletRequest request = request("/api/v1/admin/system/user/page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(USER_ID);
            when(stateResolver.isActive(USER_ID)).thenReturn(true);
            when(userTypeResolver.resolve(USER_ID)).thenReturn(LoginUserTypeResolver.TYPE_APP);

            assertFalse(interceptor.preHandle(request, response, new Object()));

            assertEquals(200, response.getStatus());
            Result<?> body = onlyWrittenBody();
            assertEquals(403, body.getCode());
            assertEquals("COMMON005", body.getErrorCode());
            stp.verify(StpUtil::logout, never());
            stp.verify(() -> StpUtil.kickout(any()), never());
        }
    }

    @Test
    @DisplayName("管理端身份门禁：user_type=ADMIN 放行并写入 userId 属性")
    void adminUserPassesAndUserIdAttributeIsSet() throws Exception {
        MockHttpServletRequest request = request("/api/v1/admin/system/user/page");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(USER_ID);
            stp.when(() -> StpUtil.getSession(false)).thenReturn(null);
            when(stateResolver.isActive(USER_ID)).thenReturn(true);
            when(userTypeResolver.resolve(USER_ID)).thenReturn(LoginUserTypeResolver.TYPE_ADMIN);

            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertEquals(USER_ID.toString(), request.getAttribute("userId"));
            assertTrue(writtenBodies.isEmpty(), "放行时不应写任何响应体");
        }
    }

    @Test
    @DisplayName("免认证白名单端点直接放行")
    void publicPathPassesWithoutLogin() throws Exception {
        MockHttpServletRequest request = request("/api/v1/all/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            assertTrue(interceptor.preHandle(request, response, new Object()));
            assertEquals(200, response.getStatus());
            assertTrue(writtenBodies.isEmpty());
            stp.verify(StpUtil::isLogin, never());
        }
    }

    @Test
    @DisplayName("Spring7解析缓存剥离contextPath后仍拒绝APP进入管理端")
    void parsedCachedPathEnforcesAdminTypeBehindContextPath() throws Exception {
        MockHttpServletRequest request = request("/astral/api/v1/admin;x/system/user/page");
        request.setContextPath("/astral");
        ServletRequestPathUtils.parseAndCache(request);
        assertAppRejectedByCachedPath(request);
    }

    @Test
    @DisplayName("UrlPathHelper字符串缓存同样保持真实管理端身份门禁")
    void legacyStringCachedPathEnforcesAdminTypeBehindContextPath() throws Exception {
        MockHttpServletRequest request = request("/astral/api/v1/admin;x/system/user/page");
        request.setContextPath("/astral");
        new UrlPathHelper().resolveAndCacheLookupPath(request);
        assertAppRejectedByCachedPath(request);
    }

    private void assertAppRejectedByCachedPath(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::isLogin).thenReturn(true);
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(USER_ID);
            when(stateResolver.isActive(USER_ID)).thenReturn(true);
            when(userTypeResolver.resolve(USER_ID)).thenReturn(LoginUserTypeResolver.TYPE_APP);
            assertFalse(interceptor.preHandle(request, response, new Object()));
            assertEquals(200, response.getStatus());
            assertEquals(403, onlyWrittenBody().getCode());
            assertEquals("COMMON005", onlyWrittenBody().getErrorCode());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    private Result<?> onlyWrittenBody() {
        assertEquals(1, writtenBodies.size(), "应当恰好写出一份响应体");
        Object body = writtenBodies.get(0);
        assertTrue(body instanceof Result, "响应体必须是统一 Result");
        return (Result<?>) body;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
