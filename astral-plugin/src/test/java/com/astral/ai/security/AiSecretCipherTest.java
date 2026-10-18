package com.astral.ai.security;

import com.astral.ai.common.AiException;
import com.astral.ai.config.AiProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API Key 加解密与凭证来源判定。
 *
 * <p>核心不变量：未配置 KEK 时<b>绝不明文落库</b>（加密抛 AI006），解密失败<b>绝不抛异常</b>
 * （返回 null，由调用方按「凭证缺失」处理）。</p>
 */
class AiSecretCipherTest {

    private static AiSecretCipher cipherWith(String secretKey) {
        AiProperties properties = new AiProperties();
        properties.setSecretKey(secretKey);
        return new AiSecretCipher(properties);
    }

    @Test
    void encryptThenDecryptRoundTrips() {
        AiSecretCipher cipher = cipherWith("unit-test-kek");
        String stored = cipher.encrypt("sk-abcdef123456");

        assertNotNull(stored);
        assertTrue(stored.startsWith("v1:"));
        assertNotEquals("sk-abcdef123456", stored);
        assertEquals("sk-abcdef123456", cipher.decrypt(stored));
    }

    @Test
    void encryptUsesRandomIvSoSameInputProducesDifferentCipherText() {
        AiSecretCipher cipher = cipherWith("unit-test-kek");
        String first = cipher.encrypt("same-secret");
        String second = cipher.encrypt("same-secret");

        assertNotEquals(first, second);
        assertEquals("same-secret", cipher.decrypt(first));
        assertEquals("same-secret", cipher.decrypt(second));
    }

    @Test
    void encryptWithoutConfiguredKeyRefusesToStorePlainText() {
        AiSecretCipher cipher = cipherWith("");
        assertFalse(cipher.isConfigured());

        AiException error = assertThrows(AiException.class, () -> cipher.encrypt("sk-plain"));
        assertEquals("AI006", error.getErrorCode());
    }

    @Test
    void blankPlainTextIsSimplyAbsent() {
        AiSecretCipher cipher = cipherWith("unit-test-kek");
        assertNull(cipher.encrypt(null));
        assertNull(cipher.encrypt("   "));
        assertNull(cipher.decrypt(null));
        assertNull(cipher.decrypt("  "));
    }

    @Test
    void decryptReturnsNullInsteadOfThrowingOnUnusableInput() {
        AiSecretCipher cipher = cipherWith("unit-test-kek");

        // 不是本模块的密文格式
        assertNull(cipher.decrypt("sk-plain-text"));
        // 前缀正确但缺少分隔符
        assertNull(cipher.decrypt("v1:abcdef"));
        // 前缀正确但 base64 损坏
        assertNull(cipher.decrypt("v1:!!!:!!!"));
    }

    @Test
    void decryptFailsClosedWhenKeyChanged() {
        String stored = cipherWith("old-kek").encrypt("sk-abcdef");
        assertNull(cipherWith("new-kek").decrypt(stored));
    }

    @Test
    void decryptFailsClosedWhenKeyRemoved() {
        String stored = cipherWith("unit-test-kek").encrypt("sk-abcdef");
        assertNull(cipherWith("").decrypt(stored));
    }

    @Test
    void describeReportsCredentialSourceWithoutLeakingSecret() {
        AiSecretCipher cipher = cipherWith("unit-test-kek");

        assertEquals("ENCRYPTED", cipher.describe("v1:aa:bb", "env:OPENAI_KEY"));
        assertEquals("SECRET_REF", cipher.describe(null, "env:OPENAI_KEY"));
        assertEquals("SECRET_REF", cipher.describe("  ", "OPENAI_KEY"));
        assertEquals("NONE", cipher.describe(null, null));
        assertEquals("NONE", cipher.describe("  ", "  "));
    }

    @Test
    void resolveSecretRefSupportsEnvPrefixAndBareName() {
        AiSecretCipher cipher = cipherWith("unit-test-kek");

        assertNull(cipher.resolveSecretRef(null));
        assertNull(cipher.resolveSecretRef("   "));
        assertNull(cipher.resolveSecretRef("env:"));
        assertNull(cipher.resolveSecretRef("env:ASTRAL_AI_TEST_MISSING_VARIABLE"));
        // PATH 在所有受支持的平台上都存在，用它证明「能找到真实环境变量」
        assertNotNull(cipher.resolveSecretRef("env:PATH"));
        assertNotNull(cipher.resolveSecretRef("PATH"));
    }

    @Test
    void constantTimeEqualsRejectsNullAndMismatch() {
        assertTrue(AiSecretCipher.constantTimeEquals("token", "token"));
        assertFalse(AiSecretCipher.constantTimeEquals("token", "tokem"));
        assertFalse(AiSecretCipher.constantTimeEquals(null, "token"));
        assertFalse(AiSecretCipher.constantTimeEquals("token", null));
        assertFalse(AiSecretCipher.constantTimeEquals("", "token"));
    }

    @Test
    void hmacSha256HexMatchesReferenceVector() {
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8",
                AiSecretCipher.hmacSha256Hex("key", "The quick brown fox jumps over the lazy dog"));
    }
}
