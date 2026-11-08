package com.astral.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookUrlPolicyTest {

    private static final String DINGTALK = "https://oapi.dingtalk.com";
    private static final Map<String, String> PUBLIC_DNS = Map.of(
            "oapi.dingtalk.com", "93.184.216.10",
            "open.feishu.cn", "93.184.216.11",
            "evil.example", "93.184.216.12",
            "example.com", "93.184.216.13");

    private static WebhookUrlPolicy policy(String allowedOrigins) {
        return new WebhookUrlPolicy(allowedOrigins, false, WebhookUrlPolicyTest::resolvePublic);
    }

    private static InetAddress[] resolvePublic(String host) throws UnknownHostException {
        String ip = PUBLIC_DNS.get(host);
        if (ip == null) {
            throw new UnknownHostException(host);
        }
        return new InetAddress[] {InetAddress.getByName(ip)};
    }

    @Test
    void allowsApprovedOriginWithRobotTokenInQuery() {
        WebhookUrlPolicy policy = policy(DINGTALK + ",https://open.feishu.cn");

        assertNotNull(policy.validate("https://oapi.dingtalk.com/robot/send?access_token=demo&sign=xyz"));
        assertEquals(2, policy.allowedOrigins().size());
    }

    @Test
    void allowsExplicitPortVariantWhenSameOrigin() {
        WebhookUrlPolicy policy = policy("https://example.com:8443");

        assertNotNull(policy.validate("https://example.com:8443/hook"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://example.com/hook"));
    }

    @Test
    void rejectsUnapprovedOrigin() {
        WebhookUrlPolicy policy = policy(DINGTALK);

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> policy.validate("https://evil.example/hook"));
        assertTrue(error.getMessage().contains("未获服务端批准"));
    }

    @Test
    void rejectsNonHttpSchemeAndUserInfoAndFragment() {
        WebhookUrlPolicy policy = policy(DINGTALK);

        assertThrows(IllegalArgumentException.class, () -> policy.validate("ftp://oapi.dingtalk.com/hook"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://user@oapi.dingtalk.com/hook"));
        assertThrows(IllegalArgumentException.class, () -> policy.validate("https://oapi.dingtalk.com/hook#frag"));
    }

    @Test
    void rejectsLoopbackAndPrivateAddressesEvenWhenOriginAllowlisted() {
        WebhookUrlPolicy loopback = new WebhookUrlPolicy(
                "http://localhost:8080", false, host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        assertThrows(IllegalArgumentException.class, () -> loopback.validate("http://localhost:8080/hook"));

        WebhookUrlPolicy privateNet = new WebhookUrlPolicy(
                "https://internal.example", false, host -> new InetAddress[] {InetAddress.getByName("10.1.2.3")});
        assertThrows(IllegalArgumentException.class, () -> privateNet.validate("https://internal.example/hook"));

        WebhookUrlPolicy dnsRebinding =
                new WebhookUrlPolicy(DINGTALK, false, host -> new InetAddress[] {InetAddress.getByName("172.16.5.5")});
        assertThrows(IllegalArgumentException.class, () -> dnsRebinding.validate(DINGTALK + "/robot/send"));
    }

    @Test
    void rejectsMetadataAddressEvenWhenPrivateNetworkOptIn() {
        WebhookUrlPolicy policy = new WebhookUrlPolicy(
                "http://169.254.169.254", true, host -> new InetAddress[] {InetAddress.getByName("169.254.169.254")});

        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> policy.validate("http://169.254.169.254/latest"));
        assertTrue(error.getMessage().contains("云元数据"));
    }

    @Test
    void rejectsIpv6MetadataBehindAllowedInternalHostname() {
        WebhookUrlPolicy policy = new WebhookUrlPolicy(
                "https://internal.example", true, host -> new InetAddress[] {InetAddress.getByName("fd00:ec2::254")});
        var error =
                assertThrows(IllegalArgumentException.class, () -> policy.validate("https://internal.example/hook"));
        assertTrue(error.getMessage().contains("云元数据"));
    }

    @Test
    void allowsPrivateNetworkOnlyWhenExplicitlyOptedIn() {
        WebhookUrlPolicy policy = new WebhookUrlPolicy(
                "http://192.168.1.20:9000", true, host -> new InetAddress[] {InetAddress.getByName("192.168.1.20")});

        assertNotNull(policy.validate("http://192.168.1.20:9000/hook"));
    }

    @Test
    void rejectsEmptyAllowlistAndUnresolvableHost() {
        WebhookUrlPolicy empty = policy("");
        assertThrows(IllegalArgumentException.class, () -> empty.validate("https://oapi.dingtalk.com/hook"));

        WebhookUrlPolicy unknown = policy(DINGTALK);
        assertThrows(IllegalArgumentException.class, () -> unknown.validate("https://unknown.example/hook"));
    }

    @Test
    void ignoresMalformedAllowlistEntries() {
        WebhookUrlPolicy policy = policy("not-a-url,https://oapi.dingtalk.com/path," + DINGTALK);

        assertEquals(1, policy.allowedOrigins().size());
    }
}
