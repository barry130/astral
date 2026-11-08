package com.astral.common.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.astral.common.annotation.RateLimit;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.annotation.RequiresSuper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** astral-common 纯工具回归：分页钳制、CSV 公式注入、日志脱敏、补丁取值、客户端 IP 与密码策略。 */
class CommonUtilsTest {

    @Test
    void pageQueryClampsUnsafeValues() {
        assertEquals(1, PageQuery.pageNum(-10));
        assertEquals(PageQuery.MAX_PAGE_NUM, PageQuery.pageNum(Integer.MAX_VALUE));
        assertEquals(1, PageQuery.pageSize(0));
        assertEquals(PageQuery.MAX_PAGE_SIZE, PageQuery.pageSize(1_000_000));
        assertEquals(50, PageQuery.limit(50, 100));
        assertEquals(1, PageQuery.limit(-1, 100));
        assertEquals(100, PageQuery.limit(1_000, 100));
    }

    @Test
    void csvExportEscapesFormulaAndRfc4180() {
        byte[] csv = CsvExportUtil.build(
                new String[] {"name", "value"},
                List.of(new Object[] {"=SUM(A1:A2)", "\t+cmd"}, new Object[] {"\u00A0@cmd", "a,b"}, new Object[] {
                    "he said \"hi\"", "plain"
                }),
                row -> row);
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("\uFEFFname,value\r\n"), "CSV 必须带 UTF-8 BOM");
        assertTrue(text.contains("'=SUM(A1:A2)"));
        assertTrue(text.contains("'\t+cmd"), "前导空白后的公式字符也必须加前缀");
        assertTrue(text.contains("'\u00A0@cmd"), "NBSP 等 Unicode 空白也不能绕过公式防护");
        assertTrue(text.contains("\"a,b\""));
        assertTrue(text.contains("\"he said \"\"hi\"\"\""));
    }

    @Test
    void logSanitizerMasksCredentialsButKeepsStructure() {
        String json = "{\"username\":\"admin\",\"password\":\"plain-text-password\","
                + "\"tokens\":[{\"accessToken\":\"abc\"}],\"nested\":{\"dbPassword\":\"p@ss\"}}";
        String masked = LogSanitizer.mask(json);
        assertFalse(masked.contains("plain-text-password"));
        assertFalse(masked.contains("abc"));
        assertFalse(masked.contains("p@ss"));
        assertTrue(masked.contains("\"username\":\"admin\""));
        assertTrue(masked.contains(LogSanitizer.MASK));
        assertEquals(masked, LogSanitizer.mask(masked), "脱敏必须幂等");

        assertEquals("token=******", LogSanitizer.mask("token=abc123"));
        assertTrue(LogSanitizer.isSensitiveKey("smtp_password"));
        assertFalse(LogSanitizer.isSensitiveKey("username"));
    }

    @Test
    void logSanitizerRedactsReadableJsonCredentialEvenWhenItContainsAMaskMarker() {
        String json = "{\"password\":\"******CONFIDENTIAL_REMAINDER\",\"username\":\"audit-fixture-user\"}";

        String masked = LogSanitizer.mask(json);

        assertFalse(masked.contains("CONFIDENTIAL_REMAINDER"), "部分打码标记不能让凭据的可读尾部进入日志");
        assertTrue(masked.contains("\"username\":\"audit-fixture-user\""), "非敏感字段须保留");
        assertEquals(masked, LogSanitizer.mask(masked), "修复部分打码绕过后仍须幂等");
    }

    @Test
    void logSanitizerRedactsReadableFormCredentialEvenWhenItStartsWithAMaskMarker() {
        String form = "username=audit-fixture-user&token=******CONFIDENTIAL_FORM_REMAINDER&enabled=true";

        String masked = LogSanitizer.mask(form);

        assertFalse(masked.contains("CONFIDENTIAL_FORM_REMAINDER"), "部分打码前缀不能让表单凭据尾部进入日志");
        assertTrue(masked.contains("username=audit-fixture-user"), "非敏感表单字段须保留");
        assertTrue(masked.contains("enabled=true"), "后续表单字段须保留");
        assertEquals(masked, LogSanitizer.mask(masked), "表单脱敏须保持幂等");
    }

    @Test
    void patchValuesDistinguishMissingFromClearing() {
        assertNull(PatchValues.blankToNull("   "));
        assertEquals(" x ", PatchValues.blankToNull(" x "), "非空白值必须原样保留，不得 trim");
        assertEquals(1, PatchValues.orDefault(null, 1));
        assertEquals("current", PatchValues.orCurrent(null, "current"));
        assertNull(PatchValues.orCurrent("", "current"));
        assertEquals("new", PatchValues.orCurrent("new", "current"));
    }

    @Test
    void clientIpIgnoresForwardedHeadersFromUntrustedPeer() {
        assertEquals(
                "203.0.113.9",
                ClientIp.resolve("1.2.3.4", "5.6.7.8", "203.0.113.9", ClientIp.DEFAULT_TRUSTED_PROXIES),
                "直连方不是可信代理时，伪造的 XFF/X-Real-IP 必须忽略");
        assertEquals(
                "1.2.3.4",
                ClientIp.resolve("1.2.3.4, 10.0.0.7", null, "127.0.0.1", ClientIp.DEFAULT_TRUSTED_PROXIES),
                "可信代理应取最右侧第一个非可信跳点");
        assertEquals(
                "198.51.100.20",
                ClientIp.resolve(null, "198.51.100.20", "192.168.1.10", ClientIp.DEFAULT_TRUSTED_PROXIES));
    }

    @Test
    void passwordPolicyRejectsWeakPasswords() {
        assertNull(PasswordPolicy.validate("abc12345", "admin"));
        assertEquals("密码至少 8 位", PasswordPolicy.validate("a1", null));
        assertEquals("密码必须同时包含字母和数字", PasswordPolicy.validate("abcdefgh", null));
        assertEquals("密码不能包含空白字符", PasswordPolicy.validate("abc 12345", null));
        assertEquals("密码不能与用户名相同", PasswordPolicy.validate("Admin123", "Admin123"));
    }

    @Test
    void classLevelSecurityAnnotationsAreInheritedByControllerSubclasses() {
        assertEquals(
                "admin:audit:demo:view",
                ChildDemoController.class.getAnnotation(RequiresPermission.class)
                        .value()[0],
                "类级权限注解必须能被子类控制器继承，否则统一基类上的权限约束会静默失效");
        assertTrue(ChildDemoController.class.isAnnotationPresent(RequiresSuper.class));
        assertTrue(ChildDemoController.class.isAnnotationPresent(RateLimit.class));
    }

    @RequiresPermission("admin:audit:demo:view")
    @RequiresSuper
    @RateLimit
    static class BaseDemoController {}

    static class ChildDemoController extends BaseDemoController {}
}
