package com.astral.ai.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import com.astral.ai.config.AiProperties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * R9：MCP 附加请求头敏感值三态契约（落库加密 / 调用还原 / 回显脱敏）。
 *
 * <p>期望值锚定审计契约原文（docs/audit/2026-11-07/backend-ai-read-recovery.md R9）：
 * 「headers 中的敏感键（Authorization/api-key/token/secret）加密存储」「出参脱敏」，
 * 并与提供方 API Key 同款取舍：KEK 未配置时拒绝明文落库（AI006）、解密失败按缺失省略。</p>
 */
class AiMcpHeaderSecurityTest {

    @Test
    void optionalHeadersCanBeOmittedOrCleared() {
        assertNull(AiMcpHeaderSecurity.protectAtRest("unit-test-kek", null, null));
        assertNull(AiMcpHeaderSecurity.protectAtRest("unit-test-kek", "   ", "{\"Authorization\":\"old\"}"));
    }

    @Test
    void maskedCredentialsSurviveHeaderNameCaseChanges() {
        String stored = AiMcpHeaderSecurity.protectAtRest(
                "unit-test-kek", "{\"Authorization\":\"Bearer private-fixture\"}", null);
        String resaved = AiMcpHeaderSecurity.protectAtRest("unit-test-kek", "{\"authorization\":\"******\"}", stored);
        assertEquals(
                "Bearer private-fixture",
                AiJson.readObject(AiMcpHeaderSecurity.revealForCall("unit-test-kek", resaved))
                        .get("authorization")
                        .asString());
    }

    @Test
    void headerValuesMustBeStringsAndLegacyMalformedValuesDoNotBreakReads() {
        for (String value : new String[] {"{}", "[]", "123", "true"}) {
            AiException error = assertThrows(
                    AiException.class,
                    () -> AiMcpHeaderSecurity.protectAtRest(
                            "unit-test-kek", "{\"Authorization\":" + value + "}", null));
            assertEquals("AI008", error.getErrorCode());
        }
        String invalid = "{\"Authorization\":{},\"X-Meta\":[],\"X-Trace-Id\":\"trace-1\"}";
        assertEquals(
                "trace-1",
                AiJson.readObject(AiMcpHeaderSecurity.maskForDisplay(invalid))
                        .get("X-Trace-Id")
                        .asString());
        assertEquals(
                "trace-1",
                AiJson.readObject(AiMcpHeaderSecurity.revealForCall("unit-test-kek", invalid))
                        .get("X-Trace-Id")
                        .asString());
    }

    @Test
    void onlyCompleteSecretReferencesCanBypassEncryption() {
        for (String value : new String[] {"${secret:SCHEME} private-fixture", "${secret:unclosed"}) {
            String json = AiJson.mapper()
                    .createObjectNode()
                    .put("Authorization", value)
                    .toString();
            AiException error =
                    assertThrows(AiException.class, () -> AiMcpHeaderSecurity.protectAtRest("", json, null));
            assertEquals("AI006", error.getErrorCode());
            String stored = AiMcpHeaderSecurity.protectAtRest("unit-test-kek", json, null);
            assertFalse(stored.contains(value));
            assertEquals(
                    value,
                    AiJson.readObject(AiMcpHeaderSecurity.revealForCall("unit-test-kek", stored))
                            .get("Authorization")
                            .asString());
        }
    }

    @Test
    void ambiguousHeaderNamesAreRejectedBeforeSaving() {
        AiException error = assertThrows(
                AiException.class,
                () -> AiMcpHeaderSecurity.protectAtRest(
                        "unit-test-kek", "{\"Authorization\":\"one\",\"authorization\":\"two\"}", null));
        assertEquals("AI008", error.getErrorCode());
    }

    private static AiSecretCipher cipherWith(String secretKey) {
        AiProperties properties = new AiProperties();
        properties.setSecretKey(secretKey);
        return new AiSecretCipher(properties);
    }

    @Test
    void protectsSensitiveHeaderValuesAtRestAndKeepsTheRestReadable() {
        String headers = "{\"Authorization\":\"Bearer abc\",\"X-Api-Key\":\"sk-1\",\"x-apikey\":\"k2\","
                + "\"X-Auth-Token\":\"t3\",\"X-Client-Secret\":\"s4\",\"X-Trace-Id\":\"trace-1\",\"X-Meta\":\"plain\"}";

        String stored = AiMcpHeaderSecurity.protectAtRest("unit-test-kek", headers, null);

        ObjectNode json = AiJson.readObject(stored);
        assertTrue(json.get("Authorization").asString().startsWith("v1:"));
        assertTrue(json.get("X-Api-Key").asString().startsWith("v1:"));
        assertTrue(json.get("x-apikey").asString().startsWith("v1:"));
        assertTrue(json.get("X-Auth-Token").asString().startsWith("v1:"));
        assertTrue(json.get("X-Client-Secret").asString().startsWith("v1:"));
        assertEquals("trace-1", json.get("X-Trace-Id").asString());
        assertEquals("plain", json.get("X-Meta").asString());

        AiSecretCipher cipher = cipherWith("unit-test-kek");
        assertEquals("Bearer abc", cipher.decrypt(json.get("Authorization").asString()));
        assertEquals("sk-1", cipher.decrypt(json.get("X-Api-Key").asString()));
    }

    @Test
    void keepsSecretReferencesAndExistingCipherTextUntouchedDuringResave() {
        String encrypted = cipherWith("unit-test-kek").encrypt("private-fixture");
        String headers = "{\"Authorization\":\"${secret:MY_MCP_TOKEN}\",\"X-Api-Key\":\"" + encrypted + "\"}";

        String stored = AiMcpHeaderSecurity.protectAtRest("unit-test-kek", headers, null);

        ObjectNode json = AiJson.readObject(stored);
        assertEquals("${secret:MY_MCP_TOKEN}", json.get("Authorization").asString());
        assertEquals(encrypted, json.get("X-Api-Key").asString());
    }

    @Test
    void unverifiedCiphertextCannotBypassCredentialProtection() {
        AiException error = assertThrows(
                AiException.class,
                () -> AiMcpHeaderSecurity.protectAtRest(
                        "unit-test-kek", "{\"Authorization\":\"v1:private-fixture\"}", null));
        assertEquals("AI008", error.getErrorCode());
        assertFalse(error.getMessage().contains("private-fixture"));
    }

    @Test
    void mergesMaskMarkerWithPreviousRowAndReEncryptsLegacyPlaintext() {
        String previous =
                "{\"Authorization\":\"v1:OLD:OLD\",\"X-Auth-Token\":\"legacy-plain-token\",\"X-Trace-Id\":\"trace-1\"}";
        String resubmitted = "{\"Authorization\":\"******\",\"X-Auth-Token\":\"******\",\"X-Trace-Id\":\"trace-2\"}";

        String stored = AiMcpHeaderSecurity.protectAtRest("unit-test-kek", resubmitted, previous);

        ObjectNode json = AiJson.readObject(stored);
        // 回显掩码表示「该敏感头未改动」，落库值原样沿用旧行密文。
        assertEquals("v1:OLD:OLD", json.get("Authorization").asString());
        // 旧行遗留明文在保存时被顺势重新加密（遗留行自愈）。
        String token = json.get("X-Auth-Token").asString();
        assertTrue(token.startsWith("v1:"));
        assertNotEquals("legacy-plain-token", token);
        assertEquals("legacy-plain-token", cipherWith("unit-test-kek").decrypt(token));
        assertEquals("trace-2", json.get("X-Trace-Id").asString());
    }

    @Test
    void refusesToStorePlaintextSecretsWhenKekIsMissing() {
        String headers = "{\"Authorization\":\"Bearer abc\"}";

        AiException error = assertThrows(AiException.class, () -> AiMcpHeaderSecurity.protectAtRest("", headers, null));
        assertEquals("AI006", error.getErrorCode());
    }

    @Test
    void rejectsUndecidableJsonInsteadOfStoringIt() {
        AiException error = assertThrows(
                AiException.class,
                () -> AiMcpHeaderSecurity.protectAtRest("unit-test-kek", "{\"Authorization\":", null));
        assertEquals("AI008", error.getErrorCode());
    }

    @Test
    void malformedCredentialsAreNotEchoedInValidationErrors() {
        AiException error = assertThrows(
                AiException.class,
                () -> AiMcpHeaderSecurity.protectAtRest(
                        "unit-test-kek", "{\"Authorization\":\"Bearer confidential-fixture", null));
        assertEquals("AI008", error.getErrorCode());
        assertFalse(error.getMessage().contains("confidential-fixture"));
    }

    @Test
    void revealForCallDecryptsCiphertextAndPassesPlainHeadersThrough() {
        String stored = AiMcpHeaderSecurity.protectAtRest(
                "unit-test-kek", "{\"Authorization\":\"Bearer abc\",\"X-Trace-Id\":\"trace-1\"}", null);

        String revealed = AiMcpHeaderSecurity.revealForCall("unit-test-kek", stored);

        ObjectNode json = AiJson.readObject(revealed);
        assertEquals("Bearer abc", json.get("Authorization").asString());
        assertEquals("trace-1", json.get("X-Trace-Id").asString());
    }

    @Test
    void revealForCallOmitsEntriesItCannotDecrypt() {
        String stored = "{\"Authorization\":\"v1:ENCRYPTED:ELSEWHERE\",\"X-Trace-Id\":\"trace-1\"}";

        String revealed = AiMcpHeaderSecurity.revealForCall("another-kek", stored);

        ObjectNode json = AiJson.readObject(revealed);
        // KEK 变更后解不开的敏感头按「凭证缺失」整条省略，绝不把密文当明文发出去。
        assertNull(json.get("Authorization"));
        assertEquals("trace-1", json.get("X-Trace-Id").asString());
    }

    @Test
    void revealForCallPassesLegacyPlaintextThrough() {
        String stored = "{\"Authorization\":\"legacy-plain\",\"X-Trace-Id\":\"trace-1\"}";

        String revealed = AiMcpHeaderSecurity.revealForCall("unit-test-kek", stored);

        ObjectNode json = AiJson.readObject(revealed);
        assertEquals("legacy-plain", json.get("Authorization").asString());
        assertEquals("trace-1", json.get("X-Trace-Id").asString());
        assertNull(AiMcpHeaderSecurity.revealForCall("unit-test-kek", null));
    }

    @Test
    void masksSensitiveValuesForDisplayRegardlessOfStoredForm() {
        String stored =
                "{\"Authorization\":\"v1:aa:bb\",\"X-Api-Key\":\"sk-plain\",\"x-auth-token\":\"${secret:T}\",\"X-Trace-Id\":\"trace-1\"}";

        String masked = AiMcpHeaderSecurity.maskForDisplay(stored);

        ObjectNode json = AiJson.readObject(masked);
        assertEquals("******", json.get("Authorization").asString());
        assertEquals("******", json.get("X-Api-Key").asString());
        assertEquals("******", json.get("x-auth-token").asString());
        assertEquals("trace-1", json.get("X-Trace-Id").asString());
        assertNull(AiMcpHeaderSecurity.maskForDisplay(null));
        assertNull(AiMcpHeaderSecurity.maskForDisplay("   "));
        assertNull(AiMcpHeaderSecurity.maskForDisplay("{oops"));
    }
}
