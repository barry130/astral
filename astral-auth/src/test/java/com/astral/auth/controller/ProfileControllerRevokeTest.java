package com.astral.auth.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.service.TokenService;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/**
 * 「下线我的某个会话」的归属校验回归（直接调用真实控制器方法，静态 Stub Sa-Token）。
 *
 * <p>对应风险：该接口按<b>会话标识</b>下线任意会话。如果只按「token 有效」就去 logout，
 * 任何登录用户都能拿别人的会话标识把别人的设备踢下线（跨租户的定向拒服务）。
 * 修复后必须先 {@code StpUtil.getLoginIdByToken(token)} 取得属主并与当前登录人比对，
 * 不一致直接业务失败、绝不 logout。</p>
 *
 * <p>同时锁住路径参数语义：入参是<b>指纹</b>（{@code GET /sessions} 给的 {@code id}），
 * 明文 token 不会被 {@code resolveTokenBySessionId} 解析出来。</p>
 */
class ProfileControllerRevokeTest {

    private static final String SESSION_ID = "0123456789abcdef0123456789abcdef";
    private static final String RAW_TOKEN = "{{SECRET_bgs4xejf}}";
    private static final String OTHER_RAW_TOKEN = "{{SECRET_bh36pzcm}}";

    private TokenService tokenService;
    private ProfileController controller;
    private MockedStatic<StpUtil> stp;

    @BeforeEach
    void setUp() {
        tokenService = mock(TokenService.class);
        controller = new ProfileController(null, null, tokenService);
        stp = mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stp.close();
    }

    @Test
    @DisplayName("会话不属于本人 → 业务失败，且绝不下线对方会话")
    void revokingOtherUsersSessionIsRejected() {
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(1001L);
        when(tokenService.resolveTokenBySessionId(SESSION_ID)).thenReturn(OTHER_RAW_TOKEN);
        stp.when(() -> StpUtil.getLoginIdByToken(OTHER_RAW_TOKEN)).thenReturn(2002L);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.revokeMySession(SESSION_ID));

        assertEquals("只能下线自己的会话", ex.getMessage());
        stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
    }

    @Test
    @DisplayName("指纹解析不出会话（已过期/伪造/传的是明文 token）→ 业务失败，不下线任何会话")
    void unknownSessionIdIsRejected() {
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(1001L);
        when(tokenService.resolveTokenBySessionId(SESSION_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.revokeMySession(SESSION_ID));

        assertEquals("会话不存在或已失效", ex.getMessage());
        stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
    }

    @Test
    @DisplayName("会话已失效（解析出 token 但 Sa-Token 侧已无属主）→ 业务失败，不下线")
    void expiredSessionIsRejected() {
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(1001L);
        when(tokenService.resolveTokenBySessionId(SESSION_ID)).thenReturn(RAW_TOKEN);
        stp.when(() -> StpUtil.getLoginIdByToken(RAW_TOKEN)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> controller.revokeMySession(SESSION_ID));

        assertEquals("会话不存在或已失效", ex.getMessage());
        stp.verify(() -> StpUtil.logoutByTokenValue(anyString()), never());
    }

    @Test
    @DisplayName("本人会话 → 精确下线该 token（不影响其它会话）")
    void revokingOwnSessionSucceeds() {
        stp.when(StpUtil::getLoginIdAsLong).thenReturn(1001L);
        when(tokenService.resolveTokenBySessionId(SESSION_ID)).thenReturn(RAW_TOKEN);
        stp.when(() -> StpUtil.getLoginIdByToken(RAW_TOKEN)).thenReturn(1001L);

        Result<Void> result = controller.revokeMySession(SESSION_ID);

        assertEquals(200, result.getCode());
        stp.verify(() -> StpUtil.logoutByTokenValue(RAW_TOKEN));
    }
}
