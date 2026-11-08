package com.astral.storage.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;

/**
 * 将一次已校验的公网 IPv4 结果固定到实际 TCP/TLS 连接，消除 DNS TOCTOU。
 *
 * <p>调用方先用 {@link #resolvePublicIpv4(String)} 做唯一一次系统解析并校验全部地址均为公网 IPv4，
 * 再把同一数组交给本解析器。传输层只会拿到这份数组，不会再次查询系统 DNS；同时返回原主机名，
 * 保持 URL 主机、SNI、证书与 hostname 校验不变。</p>
 */
public final class PinnedDnsResolver implements DnsResolver {

    private final String expectedHost;
    private final InetAddress[] addresses;

    public PinnedDnsResolver(String expectedHost, InetAddress[] addresses) {
        if (expectedHost == null || expectedHost.isBlank()) {
            throw new IllegalArgumentException("expectedHost 不能为空");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("addresses 不能为空");
        }
        this.expectedHost = normalize(expectedHost);
        this.addresses = addresses.clone();
        for (InetAddress address : this.addresses) {
            if (!isPublicIpv4(address)) {
                throw new IllegalArgumentException("只允许固定的公网 IPv4 地址");
            }
        }
    }

    /**
     * 唯一一次 DNS 解析：拒绝空结果、IPv6 与非公网 IPv4。
     */
    public static InetAddress[] resolvePublicIpv4(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException("host 为空");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses == null || addresses.length == 0) {
            throw new UnknownHostException(host);
        }
        for (InetAddress address : addresses) {
            if (!isPublicIpv4(address)) {
                throw new UnknownHostException("目标地址不是公网 IPv4");
            }
        }
        return addresses.clone();
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        requireExpectedHost(host);
        return addresses.clone();
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        requireExpectedHost(host);
        // 不发起反向 DNS；主机名保持原样，供 TLS SNI/证书校验使用。
        return expectedHost;
    }

    public String expectedHost() {
        return expectedHost;
    }

    public List<InetAddress> addresses() {
        return List.of(addresses.clone());
    }

    private void requireExpectedHost(String host) throws UnknownHostException {
        if (!normalize(host).equals(expectedHost)) {
            throw new UnknownHostException("解析器只允许固定主机: " + expectedHost);
        }
    }

    private static String normalize(String host) {
        if (host == null) {
            return "";
        }
        String value = host.trim();
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public static boolean isPublicIpv4(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (raw.length != 4) {
            return false;
        }
        int first = raw[0] & 0xFF;
        int second = raw[1] & 0xFF;
        if (first == 0 || first == 10 || first == 127) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192) {
            if (second == 168 || second == 0 || second == 2) {
                // 192.168/16 私有；192.0.0/24 文档保留；192.0.2/24 TEST-NET-1
                return false;
            }
            if (second == 88 && (raw[2] & 0xFF) == 99) {
                // 192.88.99/24 6to4 中继保留
                return false;
            }
        }
        if (first == 198) {
            if ((second & 0xFE) == 18) {
                // 198.18/15 基准测试
                return false;
            }
            if (second == 51 && (raw[2] & 0xFF) == 100) {
                // 198.51.100/24 TEST-NET-2
                return false;
            }
        }
        if (first == 203 && second == 0 && (raw[2] & 0xFF) == 113) {
            // 203.0.113/24 TEST-NET-3
            return false;
        }
        return first < 224;
    }
}
