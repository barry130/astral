package com.astral.storage.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpResponseParserTest {

    private static final HttpResponseParser PARSER = new HttpResponseParser(HttpTestSupport.policy(true));

    private static final StorageProperties.ApprovedTarget TARGET = HttpTestSupport.approvedTarget();

    private static String okBody(String url) {
        return "{\"success\":true,\"url\":\"" + url + "\",\"id\":\"remote-1\",\"size\":2048}";
    }

    private static HttpResponseParser.ParsedResult parse(
            int status, String body, HttpUploadProfile profile, Map<String, String> secrets) {
        return PARSER.parse(status, body, profile, TARGET, secrets);
    }

    @Test
    void parsesApprovedResponse() {
        var result = parse(200, okBody(HttpTestSupport.CDN + "/p/1.png"), HttpTestSupport.profile(), Map.of());

        assertTrue(result.ok());
        assertEquals("OK", result.safeCode());
        assertEquals(HttpTestSupport.CDN + "/p/1.png", result.url());
        assertEquals("remote-1", result.remoteId());
        assertEquals(Long.valueOf(2048L), result.remoteSizeBytes());
        assertNull(result.thumbUrl());
        assertTrue(result.droppedFields().isEmpty());
    }

    @Test
    void transportAndBodyFailuresUseSafeCodes() {
        assertEquals(
                "UPSTREAM_HTTP_STATUS",
                parse(500, okBody(HttpTestSupport.CDN + "/p.png"), HttpTestSupport.profile(), Map.of())
                        .safeCode());
        assertEquals(
                "EMPTY_RESPONSE",
                parse(200, "   ", HttpTestSupport.profile(), Map.of()).safeCode());
        assertEquals(
                "INVALID_JSON",
                parse(200, "not-json", HttpTestSupport.profile(), Map.of()).safeCode());
        assertEquals(
                "SUCCESS_CONDITION_FAILED",
                parse(
                                200,
                                "{\"success\":false,\"url\":\"" + HttpTestSupport.CDN + "/p.png\"}",
                                HttpTestSupport.profile(),
                                Map.of())
                        .safeCode());
        assertEquals(
                "MISSING_URL",
                parse(200, "{\"success\":true}", HttpTestSupport.profile(), Map.of())
                        .safeCode());
    }

    @Test
    void unapprovedUrlIsRejected() {
        var result = parse(200, okBody("https://evil.example.com/p.png"), HttpTestSupport.profile(), Map.of());

        assertEquals(HttpResponseParser.Kind.URL_REJECTED, result.kind());
        assertEquals("URL_NOT_APPROVED", result.safeCode());
        assertNull(result.url());
    }

    @Test
    void urlOutsideUserDeclaredOriginsIsRejected() {
        var base = HttpTestSupport.profile();
        var profile = new HttpUploadProfile(
                base.schemaVersion(),
                base.presetId(),
                base.presetVersion(),
                base.endpoint(),
                base.method(),
                base.query(),
                base.headers(),
                base.auth(),
                base.body(),
                new HttpUploadProfile.ResponseSpec(
                        base.response().success(),
                        "/url",
                        null,
                        "/id",
                        "/size",
                        "BYTES",
                        null,
                        null,
                        List.of(),
                        List.of("https://other-cdn.example.com")),
                base.limits(),
                base.timeouts());

        var result = parse(200, okBody(HttpTestSupport.CDN + "/p.png"), profile, Map.of());

        assertEquals("URL_ORIGIN_NOT_ALLOWED", result.safeCode());
    }

    @Test
    void unapprovedThumbUrlIsDroppedInsteadOfFailing() {
        String body = "{\"success\":true,\"url\":\"" + HttpTestSupport.CDN + "/p.png\","
                + "\"thumb\":\"https://evil.example.com/t.png\",\"id\":\"remote-1\"}";

        var result = parse(200, body, HttpTestSupport.profile(), Map.of());

        assertTrue(result.ok());
        assertNull(result.thumbUrl());
        assertTrue(result.droppedFields().contains("thumbUrl"));
        assertNull(result.remoteSizeBytes());
    }

    @Test
    void credentialReflectionIsQuarantined() {
        String body =
                "{\"success\":true,\"url\":\"" + HttpTestSupport.CDN + "/p.png\"," + "\"token\":\"supersecretvalue\"}";

        var result = parse(200, body, HttpTestSupport.profile(), Map.of("apiKey", "supersecretvalue"));

        assertEquals(HttpResponseParser.Kind.CREDENTIAL_REFLECTION, result.kind());
        assertEquals("CREDENTIAL_REFLECTION", result.safeCode());
    }

    @Test
    void sizeUnitIsAppliedToRemoteSize() {
        var base = HttpTestSupport.profile();
        var profile = new HttpUploadProfile(
                base.schemaVersion(),
                base.presetId(),
                base.presetVersion(),
                base.endpoint(),
                base.method(),
                base.query(),
                base.headers(),
                base.auth(),
                base.body(),
                new HttpUploadProfile.ResponseSpec(
                        base.response().success(),
                        "/url",
                        null,
                        "/id",
                        "/size",
                        "KB",
                        null,
                        null,
                        List.of(),
                        base.response().allowedUrlOrigins()),
                base.limits(),
                base.timeouts());
        String body = "{\"success\":true,\"url\":\"" + HttpTestSupport.CDN + "/p.png\",\"size\":2}";

        assertEquals(Long.valueOf(2000L), parse(200, body, profile, Map.of()).remoteSizeBytes());
    }

    @Test
    void missingProfileIsRejectedAsProfileError() {
        var exception = assertThrows(BusinessException.class, () -> PARSER.parse(200, "{}", null, TARGET, Map.of()));

        assertEquals("STORAGE034", exception.getErrorCode());
    }
}
