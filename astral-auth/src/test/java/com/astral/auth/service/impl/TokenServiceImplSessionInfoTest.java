package com.astral.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.auth.dto.TokenInfo;
import com.astral.auth.security.TokenFingerprint;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 在线会话出参 DTO 的「零明文凭据」回归。
 *
 * <p>对应缺陷：{@code GET /api/v1/admin/system/token/page}（权限 {@code admin:system:token:view}）
 * 与个人中心「我的会话」曾把 Sa-Token 的<b>明文 token</b> 放进 {@code id}/{@code token} 字段返回。
 * token 即凭据（无二次因子），因此任何持有「只读查看」权限的人都能抄走超管 token 直接接管账号。
 * 修复方式是出参只给 {@code SHA-256(token)} 前 128 比特的指纹。</p>
 *
 * <p>本测试直接打在组装 DTO 的真实方法 {@code TokenServiceImpl.toSessionInfo} 上，
 * 断言两种字段都只可能是指纹、且与明文 token 不相等 —— 只要有人把明文放回出参，这里就会红。</p>
 */
class TokenServiceImplSessionInfoTest {

    private static final String RAW_TOKEN = "{{SECRET_dhcn7afb}}";

    @Test
    @DisplayName("id/token 都只放指纹，绝不是明文 token")
    void sessionInfoNeverCarriesPlainToken() {
        TokenInfo info = TokenServiceImpl.toSessionInfo(
                RAW_TOKEN, 1001L, LocalDateTime.now().plusDays(3), "203.0.113.7", "ADMIN", LocalDateTime.now());

        assertNotEquals(RAW_TOKEN, info.getId(), "id 不允许是明文 token");
        assertNotEquals(RAW_TOKEN, info.getToken(), "token 字段不允许是明文 token");
        assertEquals(TokenFingerprint.of(RAW_TOKEN), info.getId());
        assertEquals(TokenFingerprint.of(RAW_TOKEN), info.getToken());
        assertTrue(TokenFingerprint.looksLikeFingerprint(info.getId()));
    }

    @Test
    @DisplayName("指纹形态稳定：32 位小写十六进制，同一 token 多次组装一致")
    void fingerprintShapeIsStable() {
        TokenInfo first = TokenServiceImpl.toSessionInfo(RAW_TOKEN, 1L, null, null, null, null);
        TokenInfo second = TokenServiceImpl.toSessionInfo(RAW_TOKEN, 1L, null, null, null, null);

        assertEquals(32, first.getId().length());
        assertEquals(first.getId(), second.getId());
        // 出参里不应出现明文片段的任何前缀（防止「掩码只遮一半」式回归）
        assertFalse(first.getId().contains(RAW_TOKEN.substring(0, 8)));
        assertFalse(String.valueOf(first.getToken()).contains(RAW_TOKEN));
    }

    @Test
    @DisplayName("其余展示字段原样保留（前端列不因修复而丢值）")
    void otherFieldsArePreserved() {
        LocalDateTime expire = LocalDateTime.now().plusDays(1);
        LocalDateTime created = LocalDateTime.now().minusHours(2);
        TokenInfo info = TokenServiceImpl.toSessionInfo(RAW_TOKEN, 1001L, expire, "203.0.113.7", "ADMIN", created);

        assertEquals(1001L, info.getUserId());
        assertEquals(expire, info.getExpireTime());
        assertEquals("203.0.113.7", info.getLoginIp());
        assertEquals("ADMIN", info.getDevice());
        assertEquals(created, info.getCreateTime());
        assertNotNull(info.getStatus());
    }
}
