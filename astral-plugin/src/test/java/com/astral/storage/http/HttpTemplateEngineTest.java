package com.astral.storage.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.exception.BusinessException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpTemplateEngineTest {

    @Test
    void compileSplitsLiteralsSecretsAndVariables() {
        var template = HttpTemplateEngine.compile("${publicId}.${extension}-${secret:apiKey}", "file");

        assertEquals(5, template.parts().size());
        assertEquals(java.util.Set.of("apiKey"), template.secretNames());
        assertTrue(template.referencesSecret());
        assertTrue(template.containsVariable(HttpTemplateEngine.Variable.PUBLIC_ID));
        assertTrue(template.containsVariable(HttpTemplateEngine.Variable.EXTENSION));
        assertFalse(template.containsVariable(HttpTemplateEngine.Variable.UPLOAD_ID));
    }

    @Test
    void invalidTemplatesAreRejected() {
        // 未知变量、未闭合、空变量都属于画像非法（STORAGE034），不能在渲染期才发现
        assertEquals("STORAGE034", errorCodeOf(() -> HttpTemplateEngine.compile("${unknownVar}", "file")));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpTemplateEngine.compile("${publicId", "file")));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpTemplateEngine.compile("${}", "file")));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpTemplateEngine.compile(null, "file")));
    }

    @Test
    void renderSubstitutesVariablesAndSecrets() {
        var template = HttpTemplateEngine.compile("${publicId}.${extension}-${secret:apiKey}", "file");

        String rendered = HttpTemplateEngine.render(template, HttpTestSupport.context(Map.of("apiKey", "k-1")), "file");

        assertEquals("pub_abcdef.png-k-1", rendered);
    }

    @Test
    void missingSecretFailsWithStorage036() {
        var template = HttpTemplateEngine.compile("${secret:apiKey}", "auth");

        assertEquals(
                "STORAGE036",
                errorCodeOf(() -> HttpTemplateEngine.render(template, HttpTestSupport.context(Map.of()), "auth")));
    }

    @Test
    void buildUrlAppendsQueryAndQueryAuthEncodedOnce() {
        var base = HttpTestSupport.profile();
        var profile = new HttpUploadProfile(
                base.schemaVersion(),
                base.presetId(),
                base.presetVersion(),
                base.endpoint(),
                base.method(),
                Map.of("q", "a b"),
                base.headers(),
                new HttpUploadProfile.Auth("QUERY", "key", "${secret:apiKey}", null, null),
                base.body(),
                base.response(),
                base.limits(),
                base.timeouts());

        String url = HttpTemplateEngine.buildUrl(profile, HttpTestSupport.context(Map.of("apiKey", "k 1")));

        assertEquals(HttpTestSupport.ENDPOINT + "?key=k%201&q=a%20b", url);
    }

    @Test
    void renderHeadersAppliesHeaderAuth() {
        Map<String, String> headers = HttpTemplateEngine.renderHeaders(
                HttpTestSupport.profile(), HttpTestSupport.context(Map.of("apiKey", "k-1")));

        assertEquals("k-1", headers.get("X-Api-Key"));
        assertEquals("astral", headers.get("X-Client"));
    }

    @Test
    void headerValueRejectsControlCharacters() {
        assertEquals("STORAGE034", errorCodeOf(() -> HttpTemplateEngine.checkHeaderValue("X-Test", "a\r\nb")));
        assertEquals("STORAGE034", errorCodeOf(() -> HttpTemplateEngine.checkHeaderValue("X-Test", null)));
    }

    @Test
    void queryComponentEncodesReservedCharactersOnly() {
        assertEquals("a%20b%2Fc%26d", HttpTemplateEngine.encodeQueryComponent("a b/c&d"));
        assertEquals("keep-._~", HttpTemplateEngine.encodeQueryComponent("keep-._~"));
        assertEquals("", HttpTemplateEngine.encodeQueryComponent(null));
        assertEquals(6, HttpTemplateEngine.utf8Length("中文"));
    }

    private static String errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }
}
