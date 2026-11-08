package com.astral.auth.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 会话指纹的契约回归。
 *
 * <p>对应缺陷：{@code GET /api/v1/admin/system/token/page} 与个人中心「我的会话」曾直接下发
 * 在线会话的<b>明文 token</b>。任何人只要持有 {@code admin:system:token:view}（一个「只读查看」
 * 权限）就能抄走超级管理员的 token 并直接冒充登录（Sa-Token 的 token 即凭据，无二次因子），
 * 于是「只读角色」实际等价于全员账号接管。</p>
 *
 * <p>修复后对外只给 {@code SHA-256(token)} 前 128 比特。本测试锁住三条性质：
 * ① 指纹是真正的 SHA-256 摘要（已知向量）；② 同一 token 恒等、不同 token 不同；
 * ③ 指纹形态可被吊销接口识别，而明文 token 形态被拒绝（防止后续有人「顺手」把明文再放回来）。</p>
 */
class TokenFingerprintTest {

    @Test
    @DisplayName("指纹取 SHA-256 的前 128 比特（已知向量）")
    void fingerprintIsSha256Prefix() {
        // SHA-256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ba7816bf8f01cfea414140de5dae2223", TokenFingerprint.of("abc"));
    }

    @Test
    @DisplayName("同一 token 恒等；不同 token 不同")
    void fingerprintIsStableAndDistinct() {
        String tokenA = "session-token-alpha-0001";
        String tokenB = "session-token-beta-0002";

        assertEquals(TokenFingerprint.of(tokenA), TokenFingerprint.of(tokenA));
        assertNotEquals(TokenFingerprint.of(tokenA), TokenFingerprint.of(tokenB));
    }

    @Test
    @DisplayName("空值返回 null（调用方按「无会话」处理，不产生空串指纹）")
    void nullOrEmptyHasNoFingerprint() {
        assertNull(TokenFingerprint.of(null));
        assertNull(TokenFingerprint.of(""));
    }

    @Test
    @DisplayName("指纹形态合法：32 位小写十六进制")
    void fingerprintShape() {
        String fingerprint = TokenFingerprint.of("some-session-token");
        assertEquals(32, fingerprint.length());
        assertTrue(TokenFingerprint.looksLikeFingerprint(fingerprint));
        assertEquals(fingerprint.toLowerCase(), fingerprint);
    }

    @Test
    @DisplayName("明文 token 形态不被当作指纹接收（吊销接口不接受凭据）")
    void plainTokenIsNotAcceptedAsFingerprint() {
        // 典型 Sa-Token 值：UUID 形态，含 '-'，长度 36
        assertFalse(TokenFingerprint.looksLikeFingerprint("0b9f1c2d-3e4f-5a6b-7c8d-9e0f1a2b3c4d"));
        // 长度对但还是不符合：大写十六进制
        assertFalse(TokenFingerprint.looksLikeFingerprint("BA7816BF8F01CFEA414140DE5DAE2223"));
        // 长度不足
        assertFalse(TokenFingerprint.looksLikeFingerprint("ba7816bf8f01cfea414140de5dae222"));
        assertFalse(TokenFingerprint.looksLikeFingerprint(null));
        assertFalse(TokenFingerprint.looksLikeFingerprint(""));
    }
}
