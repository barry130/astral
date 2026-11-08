package com.astral.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import com.astral.auth.security.TokenFingerprint;
import com.astral.common.exception.BusinessException;
import com.astral.dao.mapper.UserMapper;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Public service + real Sa-Token logic; only its persistence boundary is isolated. */
class TokenServiceRevocationTest {
    private StpLogic previous;
    private StpLogic logic;
    private SaTokenDaoDefaultImpl dao;
    private TokenServiceImpl service;

    @BeforeEach
    void setUp() {
        previous = StpUtil.getStpLogic();
        dao = spy(new SaTokenDaoDefaultImpl());
        logic = new StpLogic("login") {
            @Override
            public SaTokenDao getSaTokenDao() {
                return dao;
            }
        }.setConfig(new SaTokenConfig().setTokenName("audit-session"));
        StpUtil.setStpLogic(logic);
        service = new TokenServiceImpl(mock(UserMapper.class));
    }

    @AfterEach
    void restore() {
        StpUtil.setStpLogic(previous);
    }

    @Test
    void unavailableStoreCannotReportSuccessfulRevocation() {
        doThrow(new IllegalStateException("fixture storage failure"))
                .when(dao)
                .searchData(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        assertThrows(BusinessException.class, () -> service.revokeToken(TokenFingerprint.of("fixture-session")));
    }

    @Test
    void truncatedLookupIsUnknownButAnExhaustedLookupIsIdempotent() {
        String prefix = logic.getTokenName() + ":login:token:";
        doAnswer(call -> IntStream.range(0, call.getArgument(3, Integer.class))
                        .mapToObj(index -> prefix + "fixture-" + index)
                        .toList())
                .when(dao)
                .searchData(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        assertThrows(BusinessException.class, () -> service.revokeToken(TokenFingerprint.of("outside-window")));
        doAnswer(call -> java.util.List.of())
                .when(dao)
                .searchData(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        assertDoesNotThrow(() -> service.revokeToken(TokenFingerprint.of("absent")));
    }

    @Test
    void cleanupPreservesPermanentSessionsAndRevokeActuallyDeletesTheMatchingSession() {
        String token = "fixture-permanent-session";
        String key = logic.splicingKeyTokenValue(token);
        dao.set(key, "42", SaTokenDao.NEVER_EXPIRE);
        assertEquals(SaTokenDao.NEVER_EXPIRE, logic.getTokenTimeout(token));
        service.cleanExpiredTokens();
        assertNotNull(dao.get(key));
        service.revokeToken(TokenFingerprint.of(token));
        assertNull(dao.get(key));
    }

    @Test
    void bulkRevocationReportsStorageFailure() {
        doThrow(new IllegalStateException("fixture storage failure"))
                .when(dao)
                .searchData(anyString(), anyString(), anyInt(), anyInt(), anyBoolean());
        assertThrows(BusinessException.class, service::revokeAllTokens);
    }
}
