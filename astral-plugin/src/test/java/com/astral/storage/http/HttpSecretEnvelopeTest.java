package com.astral.storage.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.exception.BusinessException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpSecretEnvelopeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static HttpSecretEnvelope.Binding binding() {
        return new HttpSecretEnvelope.Binding(1001L, HttpTestSupport.ORIGIN, HttpTestSupport.PATH);
    }

    private static HttpSecretEnvelope stored(String secretName, String value) {
        return new HttpSecretEnvelope(
                1,
                HttpTestSupport.profile(),
                binding(),
                List.of(new HttpSecretEnvelope.Credential(secretName, value, "2026-11-05T10:00:00")));
    }

    private static String errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    @Test
    void parseRejectsBlankAndCorruptEnvelopes() {
        assertEquals("STORAGE034", errorCodeOf(() -> HttpSecretEnvelope.parse(null)));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpSecretEnvelope.parse("  ")));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpSecretEnvelope.parse("{\"schemaVersion\":1")));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpSecretEnvelope.parse("[]")));
    }

    @Test
    void envelopeWithUnknownSchemaVersionIsRejected() {
        // 空对象缺少 schemaVersion 与 profile，parse 阶段就要拒绝，不能等到使用期
        assertEquals("STORAGE034", errorCodeOf(() -> HttpSecretEnvelope.parse("{}")));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpSecretEnvelope.parse("{\"schemaVersion\":2}")));
    }

    @Test
    void writeThenParseRoundTrips() {
        HttpSecretEnvelope envelope = stored("apiKey", "s3cr3t-value");

        String json = HttpSecretEnvelope.write(envelope, MAPPER);
        HttpSecretEnvelope parsed = HttpSecretEnvelope.parse(json);

        parsed.requireValid();
        assertEquals("s3cr3t-value", parsed.secretValues().get("apiKey"));
        assertEquals(1, parsed.configuredCount());
        assertEquals(java.util.Set.of("apiKey"), parsed.credentialNames());
    }

    @Test
    void credentialToStringNeverLeaksValue() {
        var credential = new HttpSecretEnvelope.Credential("apiKey", "s3cr3t-value", null);

        assertFalse(credential.toString().contains("s3cr3t-value"));
        assertTrue(credential.toString().contains("apiKey"));
    }

    @Test
    void setOperationRequiresReferencedSecretName() {
        HttpSecretEnvelope envelope = stored("apiKey", "old");
        Map<String, HttpSecretEnvelope.SecretChange> changes = new LinkedHashMap<>();
        changes.put("apiKey", new HttpSecretEnvelope.SecretChange("SET", "new"));
        changes.put("other", new HttpSecretEnvelope.SecretChange("SET", "x"));

        assertEquals(
                "STORAGE037", errorCodeOf(() -> envelope.applyChanges(HttpTestSupport.profile(), binding(), changes)));
    }

    @Test
    void keepRequiresStoredSecret() {
        HttpSecretEnvelope empty = new HttpSecretEnvelope(1, HttpTestSupport.profile(), binding(), List.of());
        Map<String, HttpSecretEnvelope.SecretChange> keep =
                Map.of("apiKey", new HttpSecretEnvelope.SecretChange("KEEP", null));

        assertEquals("STORAGE036", errorCodeOf(() -> empty.applyChanges(HttpTestSupport.profile(), binding(), keep)));
    }

    @Test
    void bindingChangeForbidsKeepAndDropsOldCredentials() {
        HttpSecretEnvelope envelope = stored("apiKey", "old");
        HttpSecretEnvelope.Binding newBinding =
                new HttpSecretEnvelope.Binding(1001L, HttpTestSupport.ORIGIN, "/v2/upload");
        Map<String, HttpSecretEnvelope.SecretChange> keep =
                Map.of("apiKey", new HttpSecretEnvelope.SecretChange("KEEP", null));

        assertEquals(
                "STORAGE037", errorCodeOf(() -> envelope.applyChanges(HttpTestSupport.profile(), newBinding, keep)));

        HttpSecretEnvelope moved = envelope.applyChanges(HttpTestSupport.profile(), newBinding, null);
        assertEquals(0, moved.configuredCount());
    }

    @Test
    void applyChangesSetsClearsAndKeepsInOnePass() {
        HttpSecretEnvelope envelope = stored("apiKey", "old");
        HttpUploadProfile withTwoSecrets = HttpTestSupport.profile(
                new HttpUploadProfile.Auth("HEADER", "X-Api-Key", "${secret:apiKey}/${secret:token}", null, null));
        Map<String, HttpSecretEnvelope.SecretChange> changes = new LinkedHashMap<>();
        changes.put("apiKey", new HttpSecretEnvelope.SecretChange("SET", "new"));
        changes.put("token", new HttpSecretEnvelope.SecretChange("SET", "t-1"));

        HttpSecretEnvelope updated = envelope.applyChanges(withTwoSecrets, binding(), changes);

        assertEquals("new", updated.secretValues().get("apiKey"));
        assertEquals("t-1", updated.secretValues().get("token"));

        HttpSecretEnvelope cleared = updated.applyChanges(
                withTwoSecrets, binding(), Map.of("token", new HttpSecretEnvelope.SecretChange("CLEAR", null)));
        assertEquals(1, cleared.configuredCount());
        assertFalse(cleared.secretValues().containsKey("token"));
    }

    @Test
    void maskWhitespaceAndOversizedValuesAreRejected() {
        assertEquals("STORAGE037", errorCodeOf(() -> HttpSecretEnvelope.validateSecretValue("apiKey", "******")));
        assertEquals("STORAGE037", errorCodeOf(() -> HttpSecretEnvelope.validateSecretValue("apiKey", "   ")));
        assertEquals("STORAGE037", errorCodeOf(() -> HttpSecretEnvelope.validateSecretValue("apiKey", null)));
        assertEquals("STORAGE037", errorCodeOf(() -> HttpSecretEnvelope.validateSecretValue("apiKey", "a\rb")));
        assertEquals(
                "STORAGE037", errorCodeOf(() -> HttpSecretEnvelope.validateSecretValue("apiKey", "x".repeat(2049))));
        assertEquals("STORAGE037", errorCodeOf(() -> HttpSecretEnvelope.validateSecretValue("1bad", "v")));
        HttpSecretEnvelope.validateSecretValue("apiKey", "ok-value");
    }

    @Test
    void enabledChannelMustHaveEveryReferencedSecret() {
        HttpSecretEnvelope empty = new HttpSecretEnvelope(1, HttpTestSupport.profile(), binding(), List.of());

        assertEquals("STORAGE036", errorCodeOf(() -> empty.requireSecrets(HttpTestSupport.profile(), true)));
        empty.requireSecrets(HttpTestSupport.profile(), false);
    }

    @Test
    void storedSecretOutsideProfileIsRejected() {
        HttpSecretEnvelope envelope = stored("legacyKey", "v");

        assertEquals("STORAGE037", errorCodeOf(() -> envelope.requireSecrets(HttpTestSupport.profile(), false)));
    }

    @Test
    void secretViewsExposeNameAndConfiguredFlagOnly() {
        HttpSecretEnvelope envelope = stored("apiKey", "s3cr3t-value");

        List<HttpSecretEnvelope.SecretView> views = envelope.secretViews(HttpTestSupport.profile());

        assertEquals(1, views.size());
        assertEquals("apiKey", views.get(0).name());
        assertTrue(views.get(0).configured());
        assertNotNull(views.get(0).updatedAt());
        assertFalse(views.get(0).toString().contains("s3cr3t-value"));
    }

    @Test
    void secretNamePatternMatchesDesign() {
        assertTrueName("apiKey");
        assertTrueName("a");
        assertFalseName("1api");
        assertFalseName("api-key");
        assertFalseName("");
        assertFalseName("a".repeat(33));
    }

    private static void assertTrueName(String name) {
        assertTrue(HttpSecretEnvelope.isValidSecretName(name), name);
    }

    private static void assertFalseName(String name) {
        assertFalse(HttpSecretEnvelope.isValidSecretName(name), name);
    }
}
