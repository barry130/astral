package com.astral.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockitoSession;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.dto.LoginResponse;
import com.astral.auth.security.LoginUserTypeResolver;
import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 实际 getLoginInfo 出参的凭据最小化与强制改密门禁；无服务器或数据库。 */
class AuthServiceImplSessionInfoTest {

    private static final String RAW_TOKEN = "audit-fixture-only-not-a-real-token";
    private MockitoSession mocking;

    @Mock
    private UserMapper users;

    @Mock
    private StpInterface permissions;

    @Mock
    private LoginUserTypeResolver userTypes;

    @InjectMocks
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        mocking = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.STRICT_STUBS)
                .startMocking();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        mocking.finishMocking();
    }

    private void stubUser() {
        User user = new User();
        user.setId(41L);
        user.setUsername("audit-user");
        user.setNickname("审阅夹具");
        user.setUserType("ADMIN");
        user.setMustChangePassword(1);
        when(users.selectById(41L)).thenReturn(user);
        when(permissions.getRoleList(any(), isNull())).thenReturn(List.of("AUDIT_READER"));
        when(permissions.getPermissionList(any(), isNull())).thenReturn(List.of("admin:profile:view"));
    }

    @Test
    void restoredSessionResponseDoesNotIncludeAuthenticationToken() {
        try (MockedStatic<StpUtil> stp = loginState()) {
            stp.when(StpUtil::getTokenValue).thenReturn(RAW_TOKEN);
            JsonNode body = new ObjectMapper().valueToTree(service.getLoginInfo());
            assertFalse(body.has("token"), "已登录的信息查询不能重新暴露可用的认证凭据");
            assertFalse(body.toString().contains(RAW_TOKEN));
            assertEquals("audit-user", body.path("username").asText());
            assertEquals("AUDIT_READER", body.path("roles").path(0).asText());
        }
    }

    @Test
    void restoredSessionRetainsForcedPasswordChangeFlag() {
        try (MockedStatic<StpUtil> stp = loginState()) {
            JsonNode body = new ObjectMapper().valueToTree(service.getLoginInfo());
            assertEquals(1, body.path("mustChangePassword").asInt(), "刷新页面不得丢失强制改密标志");
        }
    }

    @Test
    void freshLoginDtoStillProvidesTheNewCallerCredential() {
        LoginResponse fresh = new LoginResponse();
        fresh.setToken(RAW_TOKEN);
        JsonNode body = new ObjectMapper().valueToTree(fresh);
        assertEquals(RAW_TOKEN, body.path("token").asText(), "新登录调用方的现有凭据契约保持");
    }

    private MockedStatic<StpUtil> loginState() {
        stubUser();
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(41L);
        stp.when(StpUtil::getSession).thenReturn(mock(SaSession.class));
        return stp;
    }
}
