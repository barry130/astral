package com.astral.common.util;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 客户端真实 IP 解析（带可信代理白名单）。
 *
 * <p><b>为什么不直接取 X-Forwarded-For</b>：该请求头是<b>客户端可任意伪造</b>的。
 * 无条件信任它会让两处安全机制同时失效：</p>
 * <ul>
 *   <li>按 IP 的登录限流（{@code @RateLimit(key="ip")}）——攻击者每次换一个 XFF 值即可绕过；</li>
 *   <li>审计日志里的来源 IP——可以随意伪造，失去取证价值。</li>
 * </ul>
 *
 * <p><b>正确做法</b>：只有当直连方（{@code remoteAddr}）本身是可信代理时才采信转发头，
 * 并且从右往左找第一个「非可信代理」的地址——因为 XFF 是逐跳追加的，
 * 最左边的值才是客户端伪造的入口。</p>
 *
 * <p>本类不依赖 Servlet API，便于 astral-common 保持零依赖；调用方自行取 header 传入。</p>
 */
public final class ClientIp {

    /** 默认可信代理：本机回环 + 私有网段（覆盖 nginx 同机、Docker 网桥、内网 LB 三种常见部署） */
    public static final String DEFAULT_TRUSTED_PROXIES =
            "127.0.0.1,::1,0:0:0:0:0:0:0:1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16";

    private ClientIp() {
    }

    /**
     * 解析客户端 IP。
     *
     * @param forwardedFor {@code X-Forwarded-For} 头原始值，可为 null
     * @param realIp       {@code X-Real-IP} 头原始值，可为 null
     * @param remoteAddr   TCP 直连方地址（{@code request.getRemoteAddr()}），可为 null
     * @param trustedProxies 可信代理列表（逗号分隔，支持单个 IP 与 CIDR），可为 null 表示用默认值
     * @return 客户端 IP；完全无法判定时返回 remoteAddr 或 {@code "unknown"}
     */
    public static String resolve(String forwardedFor, String realIp, String remoteAddr, String trustedProxies) {
        String remote = trimToNull(remoteAddr);
        Set<String> trusted = parseTrusted(trustedProxies);

        // 直连方不是可信代理：转发头一律不可信，直接返回直连地址。
        // 这一步是防伪造的关键——本地直连/直连暴露的端口，XFF 完全没有参考价值。
        if (remote == null || !isTrusted(remote, trusted)) {
            return remote == null ? "unknown" : remote;
        }

        // 直连方是可信代理：从右往左跳过所有可信代理，第一个不可信地址即真实客户端
        if (forwardedFor != null) {
            String[] hops = forwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = trimToNull(hops[i]);
                if (hop == null || "unknown".equalsIgnoreCase(hop)) {
                    continue;
                }
                if (!isTrusted(hop, trusted)) {
                    return hop;
                }
            }
        }

        String real = trimToNull(realIp);
        if (real != null && !"unknown".equalsIgnoreCase(real) && !isTrusted(real, trusted)) {
            return real;
        }

        return remote;
    }

    /**
     * 解析可信代理配置串。
     *
     * @param trustedProxies 逗号分隔的 IP / CIDR，空白或 null 时用默认值
     * @return 去重后的条目集合
     */
    public static Set<String> parseTrusted(String trustedProxies) {
        String source = (trustedProxies == null || trustedProxies.isBlank())
                ? DEFAULT_TRUSTED_PROXIES : trustedProxies;
        Set<String> set = new LinkedHashSet<>();
        for (String part : source.split(",")) {
            String p = trimToNull(part);
            if (p != null) {
                set.add(p);
            }
        }
        return set;
    }

    /**
     * 判断地址是否落在可信代理集合内（支持精确匹配与 IPv4 CIDR）。
     *
     * @param ip      待判断地址
     * @param trusted 可信代理集合
     * @return 命中返回 true
     */
    public static boolean isTrusted(String ip, Set<String> trusted) {
        if (ip == null || trusted == null || trusted.isEmpty()) {
            return false;
        }
        for (String entry : trusted) {
            if (entry.equals(ip)) {
                return true;
            }
            int slash = entry.indexOf('/');
            if (slash > 0 && matchCidr(ip, entry.substring(0, slash), entry.substring(slash + 1))) {
                return true;
            }
        }
        return false;
    }

    /** IPv4 CIDR 匹配；非 IPv4 或不合法一律返回 false（保守：不匹配即视为不可信） */
    private static boolean matchCidr(String ip, String network, String prefixLenText) {
        try {
            byte[] addr = parseIpv4(ip);
            byte[] net = parseIpv4(network);
            if (addr == null || net == null) {
                return false;
            }
            int prefixLen = Integer.parseInt(prefixLenText.trim());
            if (prefixLen < 0 || prefixLen > 32) {
                return false;
            }
            int fullBytes = prefixLen / 8;
            int restBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != net[i]) {
                    return false;
                }
            }
            if (restBits == 0) {
                return true;
            }
            int mask = 0xFF << (8 - restBits) & 0xFF;
            return (addr[fullBytes] & mask) == (net[fullBytes] & mask);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 解析点分十进制 IPv4，返回 4 字节；含 IPv6 或其他格式返回 null */
    private static byte[] parseIpv4(String ip) {
        if (ip == null || ip.indexOf(':') >= 0) {
            return null;
        }
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v = Integer.parseInt(parts[i]);
            if (v < 0 || v > 255) {
                return null;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 便捷方法：把配置串按逗号拆开校验（供启动期自检使用）。
     *
     * @param trustedProxies 配置串
     * @return 条目数组
     */
    public static String[] split(String trustedProxies) {
        return Arrays.stream(trustedProxies == null ? new String[0] : trustedProxies.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
