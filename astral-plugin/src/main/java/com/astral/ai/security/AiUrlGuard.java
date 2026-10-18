package com.astral.ai.security;

import com.astral.ai.common.AiException;
import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * AI 提供方 URL 的 SSRF 防护。
 *
 * <p>后台可配置 {@code base_url}，因此必须拒绝指向内网/回环/链路本地/元数据地址的目标，
 * 除非显式放开（本地 Ollama 属于受控例外，由 {@code allowedPrivateHosts} 白名单逐条授权）。</p>
 */
@Slf4j
public final class AiUrlGuard {

    /** 云元数据地址：任何情况下不允许 */
    private static final Set<String> METADATA_HOSTS = Set.of(
            "169.254.169.254",
            "metadata.google.internal",
            "100.100.100.200"
    );

    private AiUrlGuard() {
    }

    /**
     * 校验并规范化 base URL。
     *
     * @param rawUrl            原始 URL
     * @param allowPrivateHosts 允许的私有主机名/地址（小写，逗号分隔）
     * @return 规范化后的 URL（去掉末尾斜杠）
     */
    public static String validateBaseUrl(String rawUrl, String allowPrivateHosts) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new AiException("AI006");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new AiException("AI006");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new AiException("AI006");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new AiException("AI006");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(normalizedHost) && !isAllowed(normalizedHost, allowPrivateHosts)) {
            log.warn("[AiUrlGuard] 拒绝云元数据地址: {}", redactHosts(rawUrl));
            throw new AiException("AI006");
        }
        if (isPrivateHost(normalizedHost) && !isAllowed(normalizedHost, allowPrivateHosts)) {
            log.warn("[AiUrlGuard] 拒绝内网地址: {}", redactHosts(rawUrl));
            throw new AiException("AI006");
        }
        if (!isPrivateHost(normalizedHost)) {
            // 域名解析后仍指向内网的情况（DNS rebinding 的基础防护）
            try {
                InetAddress[] addresses = InetAddress.getAllByName(normalizedHost);
                for (InetAddress address : addresses) {
                    if (isPrivateAddress(address) && !isAllowed(normalizedHost, allowPrivateHosts)) {
                        log.warn("[AiUrlGuard] 域名解析到内网地址: {}", redactHosts(rawUrl));
                        throw new AiException("AI006");
                    }
                }
            } catch (UnknownHostException e) {
                // 解析失败不在此拦截，交给实际请求阶段报 AI006
                log.debug("[AiUrlGuard] 域名解析失败，交由请求阶段处理: {}", normalizedHost);
            }
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isAllowed(String host, String allowPrivateHosts) {
        if (allowPrivateHosts == null || allowPrivateHosts.isBlank()) {
            return false;
        }
        for (String item : allowPrivateHosts.split(",")) {
            String candidate = item.trim().toLowerCase(Locale.ROOT);
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equals(host) || candidate.equals("*")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrivateHost(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost") || host.endsWith(".local")
                || host.endsWith(".internal")) {
            return true;
        }
        // 纯 IP 直接判定
        if (host.matches("^\\d{1,3}(\\.\\d{1,3}){3}$") || host.contains(":")) {
            try {
                return isPrivateAddress(InetAddress.getByName(host));
            } catch (UnknownHostException e) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            // 100.64.0.0/10 运营商级 NAT；0.0.0.0/8；198.18.0.0/15 基准测试网段
            if (first == 0 || first == 10 || first == 127) {
                return true;
            }
            if (first == 169 && second == 254) {
                return true;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return true;
            }
            if (first == 192 && second == 168) {
                return true;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true;
            }
            if (first == 198 && (second == 18 || second == 19)) {
                return true;
            }
            return first >= 224;
        }
        if (raw.length == 16) {
            // fc00::/7 唯一本地、fe80::/10 链路本地
            int first = raw[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) {
                return true;
            }
            if (first == 0xFE && (raw[1] & 0xC0) == 0x80) {
                return true;
            }
            // IPv4 映射地址 ::ffff:a.b.c.d，递归判断
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && (raw[10] & 0xFF) == 0xFF && (raw[11] & 0xFF) == 0xFF) {
                try {
                    return isPrivateAddress(InetAddress.getByAddress(
                            new byte[]{raw[12], raw[13], raw[14], raw[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 日志里不回显完整 URL（可能带 query 凭证） */
    private static String redactHosts(String url) {
        int idx = url.indexOf('?');
        String trimmed = idx > 0 ? url.substring(0, idx) : url;
        return trimmed.length() > 120 ? trimmed.substring(0, 120) + "..." : trimmed;
    }
}
