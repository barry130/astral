package com.astral.storage.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class PinnedDnsResolverTest {

    private static InetAddress ip(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    void resolveReturnsOnlyThePinnedPublicIpv4ForTheExpectedHost() throws Exception {
        InetAddress[] pinned = {ip("93.184.216.34"), ip("1.1.1.1")};
        PinnedDnsResolver resolver = new PinnedDnsResolver("img.example.com", pinned);

        InetAddress[] resolved = resolver.resolve("IMG.EXAMPLE.COM.");

        assertArrayEquals(pinned, resolved);
        assertNotSame(pinned, resolved, "每次返回克隆，调用方不能篡改固定地址集合");
        assertEquals("img.example.com", resolver.resolveCanonicalHostname("img.example.com"));
    }

    @Test
    void resolverRejectsAnyOtherHostWithoutFallingBackToSystemDns() {
        PinnedDnsResolver resolver =
                new PinnedDnsResolver("img.example.com", new InetAddress[] {ipOrNull("93.184.216.34")});

        assertThrows(UnknownHostException.class, () -> resolver.resolve("evil.example.com"));
        assertThrows(UnknownHostException.class, () -> resolver.resolveCanonicalHostname("evil.example.com"));
    }

    @Test
    void constructorRejectsEmptyPrivateAndIpv6Addresses() throws Exception {
        assertThrows(
                IllegalArgumentException.class, () -> new PinnedDnsResolver("img.example.com", new InetAddress[0]));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PinnedDnsResolver("img.example.com", new InetAddress[] {ip("127.0.0.1")}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PinnedDnsResolver("img.example.com", new InetAddress[] {ip("10.1.2.3")}));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PinnedDnsResolver("img.example.com", new InetAddress[] {ip("2606:4700:4700::1111")}));
    }

    @Test
    void publicIpv4PredicateCoversSpecialRangesAndIpv6() throws Exception {
        assertTrue(PinnedDnsResolver.isPublicIpv4(ip("93.184.216.34")));
        assertTrue(PinnedDnsResolver.isPublicIpv4(ip("1.1.1.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("100.64.0.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("169.254.1.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("172.16.0.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("192.0.2.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("198.18.0.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("198.51.100.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("203.0.113.1")));
        assertTrue(!PinnedDnsResolver.isPublicIpv4(ip("2606:4700:4700::1111")));
    }

    @Test
    void resolvePublicIpv4AcceptsPublicLiteralAndRejectsPrivateOrIpv6() throws Exception {
        assertArrayEquals(
                new InetAddress[] {ip("93.184.216.34")}, PinnedDnsResolver.resolvePublicIpv4("93.184.216.34"));
        assertThrows(UnknownHostException.class, () -> PinnedDnsResolver.resolvePublicIpv4("127.0.0.1"));
        assertThrows(UnknownHostException.class, () -> PinnedDnsResolver.resolvePublicIpv4("::1"));
    }

    private static InetAddress ipOrNull(String literal) {
        try {
            return ip(literal);
        } catch (Exception ex) {
            throw new IllegalArgumentException(ex);
        }
    }
}
