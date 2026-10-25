package com.astral.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 操作日志脱敏。凭据一旦明文落进 {@code sys_operate_log}，
 * 「只能看日志」的账号就能读到改密/授权码/密钥 —— 这条必须是硬约束。
 */
class LogSanitizerTest {

    @Test
    void masksCredentialFieldsInJson() {
        String json = "{\"username\":\"alice\",\"password\":\"p@ssw0rd\",\"newPassword\":\"x\",\"old_password\":\"y\"}";
        String masked = LogSanitizer.mask(json);
        assertFalse(masked.contains("p@ssw0rd"), masked);
        assertFalse(masked.contains("\"x\""), masked);
        assertFalse(masked.contains("\"y\""), masked);
        assertTrue(masked.contains("alice"), "非敏感字段必须保留，否则日志没有价值");
        assertTrue(masked.contains(LogSanitizer.MASK));
    }

    @Test
    void masksCompositeAndNestedKeys() {
        String json = "{\"data\":{\"secretAccessKey\":\"AKIA123\",\"accessKeyId\":\"AKIA\","
                + "\"smtpPassword\":\"smtp-pw\",\"tokens\":[{\"accessToken\":\"t-1\",\"refreshToken\":\"r-1\"}]}}";
        String masked = LogSanitizer.mask(json);
        assertFalse(masked.contains("AKIA123"), masked);
        assertFalse(masked.contains("smtp-pw"), masked);
        assertFalse(masked.contains("t-1"), masked);
        assertFalse(masked.contains("r-1"), masked);
        // accessKeyId 是访问密钥的标识部分，单独泄露虽不足以调用 API，但能定位到具体凭据；
        // 日志里没有必须读到它的场景，一并打码（宁多打不可漏）。
        assertFalse(masked.contains("\"AKIA\""), masked);
        // 结构键与非敏感字段必须保留，否则日志失去排查价值
        assertTrue(masked.contains("data"), masked);
        assertTrue(masked.contains("accessToken"), masked);
    }

    @Test
    void masksBareScalarValues() {
        // 数字 / 布尔形态的凭据不能被漏掉（如动态验证码）
        String masked = LogSanitizer.mask("{\"totpCode\":123456,\"apiKey\":true}");
        assertFalse(masked.contains("123456"), masked);
        assertFalse(masked.contains("true"), masked);
    }

    @Test
    void maskingIsIdempotent() {
        String json = "{\"smtpPassword\":\"smtp-pw\",\"tokens\":[{\"accessToken\":\"t-1\"}]}";
        String once = LogSanitizer.mask(json);
        assertEquals(once, LogSanitizer.mask(once), "重复脱敏不得继续改写（多层链路会调用多次）");
    }

    @Test
    void masksFormAndQueryStyle() {
        String form = "username=bob&password=secret123&client_id=web&token=abc.def";
        String masked = LogSanitizer.mask(form);
        assertFalse(masked.contains("secret123"), masked);
        assertFalse(masked.contains("abc.def"), masked);
        assertTrue(masked.contains("bob"), masked);
        assertTrue(masked.contains("web"), masked);
    }

    @Test
    void keepsNonSensitivePayloadIntact() {
        String json = "{\"pageNum\":1,\"pageSize\":20,\"keyword\":\"hello\"}";
        assertEquals(json, LogSanitizer.mask(json));
    }

    @Test
    void nullAndEmptyArePassthrough() {
        assertNull(LogSanitizer.mask(null));
        assertEquals("", LogSanitizer.mask(""));
    }

    @Test
    void sensitiveKeyDetectionIsCaseAndSeparatorInsensitive() {
        assertTrue(LogSanitizer.isSensitiveKey("Access-Key-Secret"));
        assertTrue(LogSanitizer.isSensitiveKey("TOTP_SECRET"));
        assertFalse(LogSanitizer.isSensitiveKey("userName"));
        assertFalse(LogSanitizer.isSensitiveKey(null));
    }
}
