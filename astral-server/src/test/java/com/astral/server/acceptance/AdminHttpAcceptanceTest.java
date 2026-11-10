package com.astral.server.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.util.SqlIdentifiers;
import java.io.File;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperties;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the real application in a child JVM, keeping test configuration out of component scanning.
 * Requires disposable loopback fixtures and an empty Redis database 15, which is cleared during teardown.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperties({
    @EnabledIfSystemProperty(named = "audit.postgres.port", matches = "[0-9]+"),
    @EnabledIfSystemProperty(named = "audit.redis.port", matches = "[0-9]+")
})
class AdminHttpAcceptanceTest {
    private static final String AUTH = "/api/v1/all/auth";
    private static final String ROLE = "/api/v1/admin/system/role";
    private static final String SMS = "/api/v1/admin/system/sms/provider";
    private static final String USER = "/api/v1/admin/system/user";
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private Process application;
    private JdbcTemplate database;
    private LettuceConnectionFactory redis;
    private boolean ownsRedisDatabase;
    private String schema;
    private String origin;
    private Path runtime;
    private Session administrator;

    @BeforeAll
    void startIsolatedApplication() throws Exception {
        var redisConfig =
                new RedisStandaloneConfiguration("127.0.0.1", Integer.parseInt(System.getProperty("audit.redis.port")));
        redisConfig.setDatabase(15);
        redis = new LettuceConnectionFactory(redisConfig);
        redis.afterPropertiesSet();
        redis.start();
        try (var connection = redis.getConnection()) {
            assertEquals(
                    0L, connection.serverCommands().dbSize(), "HTTP acceptance requires an empty Redis database 15");
            ownsRedisDatabase = true;
        }
        schema = "http_acceptance_" + UUID.randomUUID().toString().replace("-", "");
        String databaseUrl = "jdbc:postgresql://127.0.0.1:" + System.getProperty("audit.postgres.port") + "/postgres";
        database = new JdbcTemplate(new DriverManagerDataSource(databaseUrl, "astral_audit", ""));
        database.execute(String.format("CREATE SCHEMA %s", SqlIdentifiers.requireSafe(schema)));
        Files.createDirectories(Path.of("target"));
        runtime = Files.createTempDirectory(Path.of("target").toAbsolutePath(), "http-acceptance-");
        int port;
        try (var reservation = new ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        origin = "http://127.0.0.1:" + port;
        // The application's own classpath only: nested test @Configuration classes are not application components.
        String classpath = Arrays.stream(
                        System.getProperty("surefire.test.class.path").split(File.pathSeparator))
                .map(Path::of)
                .filter(path -> !path.getFileName().toString().equals("test-classes"))
                .map(path -> path.toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
        var process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dastral.schema.sync-on-startup=false",
                "-cp",
                classpath,
                "com.astral.server.AstralApplication",
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--spring.profiles.active=http-acceptance",
                "--spring.config.location=classpath:/application.yml",
                "--management.endpoint.health.probes.enabled=true",
                "--spring.datasource.url=" + databaseUrl + "?currentSchema=" + schema,
                "--spring.datasource.username=astral_audit",
                "--spring.datasource.password=",
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.default-schema=" + schema,
                "--spring.data.redis.host=127.0.0.1",
                "--spring.data.redis.port=" + System.getProperty("audit.redis.port"),
                "--spring.data.redis.password=",
                "--spring.data.redis.database=15",
                "--astral.auth.cookie-secure=false",
                "--astral.auth.cookie-same-site=Strict",
                "--astral.auth.rsa-key-path=./data/rsa-key.pair",
                "--astral.plugins.storage.upload-ticket-key=",
                "--astral.plugins.storage.origin-shared-secret=",
                "--astral.plugins.storage.download-signing-key=",
                "--astral.plugins.ai.secret-key=");
        // Inherit only OS essentials, never a developer's application credentials or feature overrides.
        Set<String> osEnvironment =
                Set.of("SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP", "HOME", "USERPROFILE", "LANG");
        process.environment().keySet().removeIf(key -> !osEnvironment.contains(key.toUpperCase(java.util.Locale.ROOT)));
        process.directory(runtime.toFile())
                .redirectErrorStream(true)
                .redirectOutput(runtime.resolve("application.log").toFile());
        application = process.start();
        Files.writeString(
                runtime.resolve("fixture.json"),
                json.writeValueAsString(Map.of(
                        "pid", application.pid(), "origin", origin, "schema", schema, "runtime", runtime.toString())));
        long deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos();
        while (System.nanoTime() < deadline) {
            assertTrue(application.isAlive(), "Application exited; inspect " + runtime.resolve("application.log"));
            try {
                Reply readiness = request("GET", "/actuator/health/readiness", null, Map.of());
                if (readiness.status() == 200
                        && "UP".equals(readiness.body().path("status").asText())) {
                    assertReply(request("GET", AUTH + "/public-key", null, Map.of()), 200, 200);
                    return;
                }
            } catch (java.io.IOException unavailable) {
                // Tomcat has not started listening yet.
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Application startup timed out; inspect " + runtime.resolve("application.log"));
    }

    @AfterAll
    void stopIsolatedApplication() throws Exception {
        try {
            if (application != null) {
                application.destroy();
                if (!application.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                    application.destroyForcibly();
                    assertTrue(application.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
                }
            }
        } finally {
            http.close();
            try {
                if (database != null && schema != null) {
                    database.execute(
                            String.format("DROP SCHEMA IF EXISTS %s CASCADE", SqlIdentifiers.requireSafe(schema)));
                }
            } finally {
                if (redis != null) {
                    try (var connection = redis.getConnection()) {
                        if (ownsRedisDatabase) {
                            connection.serverCommands().flushDb();
                        }
                    } finally {
                        redis.destroy();
                    }
                }
            }
        }
    }

    @Test
    void cookieLoginRestoresSessionAndLogoutRevokesIt() throws Exception {
        Reply anonymous = request("GET", AUTH + "/info", null, Map.of());
        assertReply(anonymous, 200, 401);
        assertEquals("AUTH001", anonymous.body().path("errorCode").asText());

        Session session = login("admin", "admin");
        Reply restored = request("GET", AUTH + "/info", null, session.headers(false));
        assertReply(restored, 200, 200);
        assertEquals("admin", restored.body().path("data").path("username").asText());
        assertEquals(
                session.csrf(), restored.body().path("data").path("csrfToken").asText());
        assertFalse(restored.body().path("data").has("token"));

        Reply logout = request("POST", AUTH + "/logout", Map.of(), session.headers(true));
        assertReply(logout, 200, 200);
        assertTrue(logout.cookies().stream()
                .anyMatch(value -> value.startsWith("satoken=") && value.contains("Max-Age=0")));
        assertReply(request("GET", AUTH + "/info", null, session.headers(false)), 200, 401);
    }

    @Test
    void roleWritesRequireCsrfAndPreserveOmittedAndProtectedFields() throws Exception {
        Session admin = administrator();
        String code = "HTTP_PATCH";
        Map<String, Object> role =
                Map.of("roleCode", code, "roleName", "Original role", "sort", 7, "status", 1, "isSuper", 1);
        Reply missingCsrf = request("POST", ROLE, role, admin.headers(false));
        assertReply(missingCsrf, 200, 403);
        assertEquals("AUTH013", missingCsrf.body().path("errorCode").asText());
        assertFalse(readData(ROLE + "/all", admin).toString().contains(code));

        assertReply(request("POST", ROLE, role, admin.headers(true)), 200, 200);
        JsonNode created = find(readData(ROLE + "/all", admin), "roleCode", code);
        assertEquals(0, created.path("isSuper").asInt());
        String detail = ROLE + "/" + created.path("id").asLong();
        assertReply(
                request(
                        "PUT",
                        detail,
                        Map.of("roleName", "Updated role", "roleCode", "FORBIDDEN", "isSuper", 1),
                        admin.headers(true)),
                200,
                200);
        JsonNode updated = readData(detail, admin);
        assertEquals("Updated role", updated.path("roleName").asText());
        assertEquals(code, updated.path("roleCode").asText());
        assertEquals(0, updated.path("isSuper").asInt());
        assertEquals(7, updated.path("sort").asInt());
        assertEquals(1, updated.path("status").asInt());

        Reply invalid = request("PUT", detail, Map.of("roleName", "x".repeat(129)), admin.headers(true));
        assertReply(invalid, 200, 320);
        assertTrue(invalid.body().path("msg").asText().contains("roleName"));
        assertEquals(updated, readData(detail, admin));
    }

    @Test
    void smsPartialWritesPreserveConfigurationAndNeverExposeCredentials() throws Exception {
        Session admin = administrator();
        assertReply(
                request(
                        "POST",
                        SMS,
                        Map.of(
                                "providerName",
                                "HTTP SMS",
                                "providerType",
                                " mock ",
                                "accessSecret",
                                "fixture-secret",
                                "signName",
                                "Fixture",
                                "weight",
                                3,
                                "enabled",
                                1),
                        admin.headers(true)),
                200,
                200);
        JsonNode created = find(readData(SMS + "/page", admin).path("records"), "providerName", "HTTP SMS");
        assertEquals("MOCK", created.path("providerType").asText());
        assertFalse(created.hasNonNull("accessSecret"));
        String detail = SMS + "/" + created.path("id").asLong();
        assertFalse(readData(detail, admin).hasNonNull("accessSecret"));

        assertReply(request("PUT", detail, Map.of("enabled", 0), admin.headers(true)), 200, 200);
        JsonNode disabled = readData(detail, admin);
        assertEquals(0, disabled.path("enabled").asInt());
        assertEquals("HTTP SMS", disabled.path("providerName").asText());
        assertEquals("MOCK", disabled.path("providerType").asText());
        assertEquals("Fixture", disabled.path("signName").asText());
        assertEquals(3, disabled.path("weight").asInt());
        assertFalse(disabled.hasNonNull("accessSecret"));

        Reply blankSecret = request(
                "PUT", detail, Map.of("providerName", "Must not persist", "accessSecret", " "), admin.headers(true));
        assertReply(blankSecret, 200, 300);
        assertEquals("MAIL007", blankSecret.body().path("errorCode").asText());
        assertEquals(disabled, readData(detail, admin));
        Reply invalidType = request("PUT", detail, Map.of("providerType", "unknown"), admin.headers(true));
        assertReply(invalidType, 200, 300);
        assertEquals("SMS006", invalidType.body().path("errorCode").asText());
        assertEquals(disabled, readData(detail, admin));
    }

    @Test
    void permissionGrantsAndRevocationsApplyToTheExistingSession() throws Exception {
        Session admin = administrator();
        assertReply(request("GET", ROLE + "/all", null, Map.of()), 200, 401);
        assertReply(
                request("POST", ROLE, Map.of("roleCode", "HTTP_DELEGATE", "roleName", "Delegate"), admin.headers(true)),
                200,
                200);
        long roleId = find(readData(ROLE + "/all", admin), "roleCode", "HTTP_DELEGATE")
                .path("id")
                .asLong();
        long userId = createUser("http_delegate", "ADMIN", admin);
        assertReply(
                request("PUT", USER + "/" + userId + "/roles", Map.of("roleIds", List.of(roleId)), admin.headers(true)),
                200,
                200);
        Session delegated = login("http_delegate", "Fixture123!");
        assertReply(request("GET", ROLE + "/all", null, delegated.headers(false)), 200, 403);
        assertReply(
                request("PUT", ROLE + "/" + roleId, Map.of("roleName", "Denied"), delegated.headers(true)), 200, 403);
        assertEquals(
                "Delegate",
                readData(ROLE + "/" + roleId, admin).path("roleName").asText());

        List<Long> permissionIds = new ArrayList<>();
        for (JsonNode permission :
                readData("/api/v1/admin/system/permission/tree", admin).findParents("permissionCode")) {
            if (Set.of("admin:system:role:view", "admin:system:role:edit")
                    .contains(permission.path("permissionCode").asText())) {
                permissionIds.add(permission.path("id").asLong());
            }
        }
        assertEquals(2, permissionIds.size());
        assertReply(
                request(
                        "PUT",
                        ROLE + "/" + roleId + "/permissions",
                        Map.of("permissionIds", permissionIds),
                        admin.headers(true)),
                200,
                200);
        assertReply(request("GET", ROLE + "/all", null, delegated.headers(false)), 200, 200);
        assertReply(
                request(
                        "PUT",
                        ROLE + "/" + roleId,
                        Map.of("roleName", "Delegated change", "isSuper", 1),
                        delegated.headers(true)),
                200,
                200);
        assertEquals(
                "Delegated change",
                readData(ROLE + "/" + roleId, admin).path("roleName").asText());
        assertEquals(0, readData(ROLE + "/" + roleId, admin).path("isSuper").asInt());
        assertReply(
                request(
                        "POST",
                        ROLE,
                        Map.of("roleCode", "DENIED_ESCALATION", "roleName", "Forbidden"),
                        delegated.headers(true)),
                200,
                403);
        assertReply(
                request(
                        "PUT",
                        ROLE + "/" + roleId + "/permissions",
                        Map.of("permissionIds", permissionIds),
                        delegated.headers(true)),
                200,
                403);

        assertReply(
                request(
                        "PUT",
                        ROLE + "/" + roleId + "/permissions",
                        Map.of("permissionIds", List.of()),
                        admin.headers(true)),
                200,
                200);
        assertReply(request("GET", ROLE + "/all", null, delegated.headers(false)), 200, 403);
        assertReply(
                request("PUT", ROLE + "/" + roleId, Map.of("roleName", "Revoked"), delegated.headers(true)), 200, 403);
        assertEquals(
                "Delegated change",
                readData(ROLE + "/" + roleId, admin).path("roleName").asText());
        assertReply(request("GET", AUTH + "/info", null, delegated.headers(false)), 200, 200);
    }

    @Test
    void appIdentityCannotUseAdminRoutesEvenWithAnAssignedAdminRole() throws Exception {
        Session admin = administrator();
        long appId = createUser("http_app", "APP", admin);
        long superRoleId =
                find(readData(ROLE + "/all", admin), "isSuper", "1").path("id").asLong();
        assertReply(
                request(
                        "PUT",
                        USER + "/" + appId + "/roles",
                        Map.of("roleIds", List.of(superRoleId)),
                        admin.headers(true)),
                200,
                200);
        Reply login = request(
                "POST", "/api/v1/app/user/login", Map.of("username", "http_app", "password", "Fixture123!"), Map.of());
        assertReply(login, 200, 200);
        String token = login.body().path("data").path("token").asText();
        assertFalse(token.isBlank());
        assertEquals(
                "APP", login.body().path("data").path("user").path("userType").asText());
        Reply denied = request("GET", ROLE + "/all", null, Map.of("satoken", token));
        assertReply(denied, 200, 403);
        assertEquals("COMMON005", denied.body().path("errorCode").asText());
        Reply deniedWrite = request(
                "POST", ROLE, Map.of("roleCode", "APP_FORBIDDEN", "roleName", "Forbidden"), Map.of("satoken", token));
        assertReply(deniedWrite, 200, 403);
        assertFalse(readData(ROLE + "/all", admin).toString().contains("APP_FORBIDDEN"));
        assertReply(request("POST", "/api/v1/app/user/logout", Map.of(), Map.of("satoken", token)), 200, 200);
    }

    @Test
    void invalidRequestsUseTheDocumentedTransportAndBusinessCodes() throws Exception {
        Session admin = administrator();
        assertError(requestJson("POST", ROLE, "{broken-json", admin.headers(true)), 200, 320, "COMMON002");
        assertError(
                request("POST", ROLE, Map.of("roleCode", "MISSING_NAME"), admin.headers(true)), 200, 320, "COMMON002");
        assertError(
                request(
                        "POST",
                        ROLE,
                        Map.of("roleCode", "LONG_NAME", "roleName", "x".repeat(129)),
                        admin.headers(true)),
                200,
                320,
                "COMMON002");
        assertError(request("GET", ROLE + "/page?pageNum=invalid", null, admin.headers(false)), 200, 320, "COMMON002");
        assertError(
                request("GET", "/api/v1/admin/system/dict/data/byCode", null, admin.headers(false)),
                200,
                320,
                "COMMON004");
        assertError(
                request("GET", "/api/v1/admin/http-acceptance-missing", null, admin.headers(false)),
                404,
                404,
                "COMMON003");
        assertError(request("DELETE", AUTH + "/public-key", null, admin.headers(true)), 405, 405, "COMMON008");
        String roles = readData(ROLE + "/all", admin).toString();
        assertFalse(roles.contains("MISSING_NAME"));
        assertFalse(roles.contains("LONG_NAME"));
    }

    @Test
    void userProfilePatchKeepsCredentialsIdentityAndOmittedFields() throws Exception {
        Session admin = administrator();
        long userId = createUser("http_profile", "ADMIN", admin);
        String detail = USER + "/" + userId;
        assertReply(
                request(
                        "PUT",
                        detail,
                        Map.of(
                                "nickname",
                                "Profile change",
                                "password",
                                "Replacement123!",
                                "userType",
                                "APP",
                                "status",
                                0),
                        admin.headers(true)),
                200,
                200);
        JsonNode updated = readData(detail, admin);
        assertEquals("http_profile", updated.path("username").asText());
        assertEquals("Profile change", updated.path("nickname").asText());
        assertEquals("ADMIN", updated.path("userType").asText());
        assertEquals(1, updated.path("status").asInt());
        assertFalse(updated.hasNonNull("password"));
        assertReply(
                request(
                        "PUT",
                        detail,
                        Map.of("nickname", "Must not persist", "phone", "1".repeat(33)),
                        admin.headers(true)),
                200,
                320);
        assertEquals(updated, readData(detail, admin));
        Session user = login("http_profile", "Fixture123!");
        assertEquals(
                "Profile change",
                readData(AUTH + "/info", user).path("nickname").asText());
    }

    private long createUser(String username, String type, Session admin) throws Exception {
        assertReply(
                request(
                        "POST",
                        USER,
                        Map.of("username", username, "password", "Fixture123!", "userType", type, "status", 1),
                        admin.headers(true)),
                200,
                200);
        JsonNode user =
                find(readData(USER + "/page?username=" + username, admin).path("records"), "username", username);
        assertFalse(user.hasNonNull("password"));
        return user.path("id").asLong();
    }

    private Session administrator() throws Exception {
        if (administrator == null) {
            administrator = login("admin", "admin");
        }
        return administrator;
    }

    private JsonNode readData(String path, Session session) throws Exception {
        Reply reply = request("GET", path, null, session.headers(false));
        assertReply(reply, 200, 200);
        return reply.body().path("data");
    }

    private static JsonNode find(JsonNode records, String field, String value) {
        for (JsonNode record : records) {
            if (value.equals(record.path(field).asText())) {
                return record;
            }
        }
        throw new AssertionError("Expected record not returned: " + field + "=" + value);
    }

    private Session login(String username, String password) throws Exception {
        Reply key = request("GET", AUTH + "/public-key", null, Map.of());
        assertReply(key, 200, 200);
        byte[] encoded = Base64.getDecoder()
                .decode(key.body().path("data").path("publicKey").asText());
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                publicKey,
                new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        String encrypted =
                Base64.getEncoder().encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
        Reply login = request("POST", AUTH + "/login", Map.of("username", username, "password", encrypted), Map.of());
        assertReply(login, 200, 200);
        assertTrue(login.cookies().stream()
                .anyMatch(value -> value.startsWith("satoken=")
                        && value.contains("HttpOnly")
                        && value.contains("SameSite=Strict")));
        String cookies =
                login.cookies().stream().map(value -> value.split(";", 2)[0]).collect(Collectors.joining("; "));
        return new Session(cookies, login.body().path("data").path("csrfToken").asText());
    }

    private Reply request(String method, String path, Object body, Map<String, String> headers) throws Exception {
        return requestJson(method, path, body == null ? null : json.writeValueAsString(body), headers);
    }

    private Reply requestJson(String method, String path, String body, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(15));
        headers.forEach(request::header);
        request.header("X-App-Ut", "web");
        request.header("X-App-Version", "1.0.0-http-acceptance");
        request.header("X-Device", "HttpClient fixture");
        request.header("X-OS", "Fixture OS");
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(
                response.statusCode(),
                json.readTree(response.body()),
                response.headers().allValues("set-cookie"));
    }

    private static void assertReply(Reply reply, int status, int code) {
        assertEquals(status, reply.status(), () -> "Unexpected HTTP status; body=" + reply.body());
        assertEquals(code, reply.body().path("code").asInt(), () -> "Unexpected business code; body=" + reply.body());
    }

    private static void assertError(Reply reply, int status, int code, String errorCode) {
        assertReply(reply, status, code);
        assertEquals(errorCode, reply.body().path("errorCode").asText());
        assertFalse(reply.body().path("msg").asText().isBlank());
    }

    private record Reply(int status, JsonNode body, List<String> cookies) {}

    private record Session(String cookies, String csrf) {
        Map<String, String> headers(boolean write) {
            return write ? Map.of("Cookie", cookies, "X-CSRF-Token", csrf) : Map.of("Cookie", cookies);
        }
    }
}
