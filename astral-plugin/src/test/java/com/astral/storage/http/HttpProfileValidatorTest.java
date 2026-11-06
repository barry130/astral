package com.astral.storage.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HttpProfileValidatorTest {

    private static HttpProfileValidator validator(boolean enabled) {
        return new HttpProfileValidator(
                HttpTestSupport.policy(enabled), JsonMapper.builder().build());
    }

    private static String errorCodeOf(Runnable action) {
        return assertThrows(BusinessException.class, action::run).getErrorCode();
    }

    @Test
    void validProfileIsNormalizedToApprovedTarget() {
        var validated = validator(true).validate(HttpTestSupport.profile());

        assertEquals(HttpTestSupport.ENDPOINT, validated.profile().endpoint());
        assertEquals(Integer.valueOf(1), validated.profile().schemaVersion());
        assertEquals("POST", validated.profile().method());
        assertEquals(HttpTestSupport.PATH, validated.target().getPath());
        assertEquals("X-Api-Key", validated.profile().auth().name());
        assertEquals(1, validated.profile().response().success().size());
        assertEquals(
                List.of(HttpTestSupport.CDN), validated.profile().response().allowedUrlOrigins());
    }

    @Test
    void endpointOutsideApprovedTargetsIsRejected() {
        var foreign = HttpTestSupport.profile(
                HttpTestSupport.profile().auth(),
                "https://evil.example.com/upload",
                Map.of(),
                List.of(HttpTestSupport.CDN),
                1048576L);
        assertEquals("STORAGE035", errorCodeOf(() -> validator(true).validate(foreign)));

        var otherPath = HttpTestSupport.profile(
                HttpTestSupport.profile().auth(),
                HttpTestSupport.ORIGIN + "/other",
                Map.of(),
                List.of(HttpTestSupport.CDN),
                1048576L);
        assertEquals("STORAGE035", errorCodeOf(() -> validator(true).validate(otherPath)));
    }

    @Test
    void plainHttpAndQueryEndpointsAreRejected() {
        var insecure = HttpTestSupport.profile(
                HttpTestSupport.profile().auth(),
                HttpTestSupport.ORIGIN.replace("https://", "http://") + HttpTestSupport.PATH,
                Map.of(),
                List.of(HttpTestSupport.CDN),
                1048576L);
        assertEquals("STORAGE035", errorCodeOf(() -> validator(true).validate(insecure)));

        var withQuery = HttpTestSupport.profile(
                HttpTestSupport.profile().auth(),
                HttpTestSupport.ENDPOINT + "?x=1",
                Map.of(),
                List.of(HttpTestSupport.CDN),
                1048576L);
        assertEquals("STORAGE035", errorCodeOf(() -> validator(true).validate(withQuery)));
    }

    @Test
    void structuralViolationsAreRejectedAsProfileErrors() {
        // NONE 认证无法携带任何凭据，等于给上游发匿名请求 —— 直接判定画像非法
        var noAuth = HttpTestSupport.profile(new HttpUploadProfile.Auth("NONE", null, null, null, null));
        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(noAuth)));

        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(null)));

        var wrongVersion = new HttpUploadProfile(
                2,
                null,
                null,
                HttpTestSupport.ENDPOINT,
                "POST",
                Map.of(),
                Map.of(),
                HttpTestSupport.profile().auth(),
                HttpTestSupport.profile().body(),
                HttpTestSupport.profile().response(),
                HttpTestSupport.profile().limits(),
                HttpTestSupport.profile().timeouts());
        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(wrongVersion)));

        var getMethod = new HttpUploadProfile(
                1,
                null,
                null,
                HttpTestSupport.ENDPOINT,
                "GET",
                Map.of(),
                Map.of(),
                HttpTestSupport.profile().auth(),
                HttpTestSupport.profile().body(),
                HttpTestSupport.profile().response(),
                HttpTestSupport.profile().limits(),
                HttpTestSupport.profile().timeouts());
        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(getMethod)));
    }

    @Test
    void wildcardMimeAndOversizedLimitAreRejected() {
        var wildcard = new HttpUploadProfile(
                1,
                null,
                null,
                HttpTestSupport.ENDPOINT,
                "POST",
                Map.of(),
                Map.of(),
                HttpTestSupport.profile().auth(),
                HttpTestSupport.profile().body(),
                HttpTestSupport.profile().response(),
                new HttpUploadProfile.Limits(1048576L, List.of("image/*")),
                HttpTestSupport.profile().timeouts());
        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(wildcard)));

        var oversized = HttpTestSupport.profile(
                HttpTestSupport.profile().auth(),
                HttpTestSupport.ENDPOINT,
                Map.of(),
                List.of(HttpTestSupport.CDN),
                HttpUploadProfile.HARD_MAX_BYTES + 1);
        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(oversized)));
    }

    @Test
    void unapprovedOutputOriginIsRejected() {
        var profile = new HttpUploadProfile(
                1,
                null,
                null,
                HttpTestSupport.ENDPOINT,
                "POST",
                Map.of(),
                Map.of(),
                HttpTestSupport.profile().auth(),
                HttpTestSupport.profile().body(),
                new HttpUploadProfile.ResponseSpec(
                        List.of(new HttpUploadProfile.Condition("/success", "EQ", "BOOLEAN", "true")),
                        "/url",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of("https://evil.example.com")),
                new HttpUploadProfile.Limits(1048576L, List.of("image/png")),
                new HttpUploadProfile.Timeouts(30000));

        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(profile)));
    }

    @Test
    void malformedSuccessConditionsAreRejected() {
        var existsWithValue = new HttpUploadProfile(
                1,
                null,
                null,
                HttpTestSupport.ENDPOINT,
                "POST",
                Map.of(),
                Map.of(),
                HttpTestSupport.profile().auth(),
                HttpTestSupport.profile().body(),
                new HttpUploadProfile.ResponseSpec(
                        List.of(new HttpUploadProfile.Condition("/success", "EXISTS", "BOOLEAN", "true")),
                        "/url",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(HttpTestSupport.CDN)),
                new HttpUploadProfile.Limits(1048576L, List.of("image/png")),
                new HttpUploadProfile.Timeouts(30000));
        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(existsWithValue)));

        var wildcardPointer = new HttpUploadProfile(
                1,
                null,
                null,
                HttpTestSupport.ENDPOINT,
                "POST",
                Map.of(),
                Map.of(),
                HttpTestSupport.profile().auth(),
                HttpTestSupport.profile().body(),
                new HttpUploadProfile.ResponseSpec(
                        List.of(new HttpUploadProfile.Condition("/data/*", "EXISTS", null, null)),
                        "/url",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(HttpTestSupport.CDN)),
                new HttpUploadProfile.Limits(1048576L, List.of("image/png")),
                HttpTestSupport.profile().timeouts());
        assertEquals("STORAGE034", errorCodeOf(() -> validator(true).validate(wildcardPointer)));
    }

    @Test
    void jsonPointerValidationFollowsRfc6901Subset() {
        assertTrue(HttpProfileValidator.isValidPointer("/url"));
        assertTrue(HttpProfileValidator.isValidPointer("/data/0/url"));
        assertTrue(HttpProfileValidator.isValidPointer("/a~0b~1c"));

        assertFalse(HttpProfileValidator.isValidPointer(null));
        assertFalse(HttpProfileValidator.isValidPointer(""));
        assertFalse(HttpProfileValidator.isValidPointer("url"));
        assertFalse(HttpProfileValidator.isValidPointer("/data/*"));
        assertFalse(HttpProfileValidator.isValidPointer("/a~2b"));
        assertFalse(HttpProfileValidator.isValidPointer("/a//b"));
        assertFalse(HttpProfileValidator.isValidPointer("/" + "a/".repeat(12) + "a"));
    }

    @Test
    void disabledFeatureDoesNotBreakOfflineValidation() {
        // 离线校验只看画像结构，不因总开关关闭而拒绝（真实上传/测试才需要开关）
        var validated = validator(false).validate(HttpTestSupport.profile());

        assertEquals(HttpTestSupport.ENDPOINT, validated.profile().endpoint());
    }

    @Test
    void approvedTargetsComeFromDeploymentConfigOnly() {
        StorageProperties.ApprovedTarget target = HttpTestSupport.approvedTarget();

        assertEquals(HttpTestSupport.ORIGIN, target.getOrigin());
        assertEquals(List.of(HttpTestSupport.CDN), target.getUrlOrigins());
        assertTrue(HttpTestSupport.policy(true).enabled());
        assertFalse(HttpTestSupport.policy(false).enabled());
    }
}
