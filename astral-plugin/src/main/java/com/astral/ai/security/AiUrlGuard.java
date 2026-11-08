package com.astral.ai.security;

import com.astral.ai.common.AiException;
import com.astral.common.http.NetworkAddresses;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/** Provider requests resolve once inside the transport deadline and pin every approved address. */
public final class AiUrlGuard {
    private static final Set<String> METADATA_HOSTS =
            Set.of("169.254.169.254", "metadata.google.internal", "100.100.100.200", "fd00:ec2::254");

    private AiUrlGuard() {}

    /** Configuration validation performs no DNS lookup; request-time approval is authoritative. */
    public static String validateBaseUrl(String rawUrl, String allowPrivateHosts) {
        try {
            URI uri = URI.create(rawUrl == null ? "" : rawUrl.trim());
            String host = validateShape(uri);
            boolean allowed = isAllowed(host, allowPrivateHosts);
            if ((host.equals("localhost")
                            || host.endsWith(".localhost")
                            || host.endsWith(".local")
                            || host.endsWith(".internal"))
                    && !allowed) {
                throw new IOException("私有目标未获批准");
            }
            if (host.matches("[0-9.]+") || host.contains(":")) {
                validateAddresses(new InetAddress[] {InetAddress.getByName(host)}, allowed);
            }
            String normalized = uri.toString();
            while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
            return normalized;
        } catch (IOException | RuntimeException invalid) {
            throw new AiException("AI006", "模型服务地址无效或未获批准");
        }
    }

    public static InetAddress[] approve(URI target, String allowPrivateHosts) throws IOException {
        String host = validateShape(target);
        InetAddress[] addresses = InetAddress.getAllByName(host);
        validateAddresses(addresses, isAllowed(host, allowPrivateHosts));
        return addresses;
    }

    public static String configuredPrivateHosts() {
        return System.getProperty(
                "astral.plugins.ai.allow-private-hosts", System.getenv("ASTRAL_AI_ALLOW_PRIVATE_HOSTS"));
    }

    private static String validateShape(URI target) throws IOException {
        if (target.getHost() == null
                || target.getUserInfo() != null
                || target.getFragment() != null
                || !("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme()))) {
            throw new IOException("模型服务地址无效");
        }
        String host = target.getHost().toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (METADATA_HOSTS.contains(host)) throw new IOException("禁止访问元数据地址");
        return host;
    }

    private static void validateAddresses(InetAddress[] addresses, boolean allowed) throws IOException {
        if (addresses.length == 0) throw new IOException("模型服务地址无法解析");
        for (InetAddress address : addresses) {
            if (NetworkAddresses.isMetadata(address) || (!allowed && !NetworkAddresses.isPublic(address))) {
                throw new IOException("模型服务地址未获批准");
            }
        }
    }

    private static boolean isAllowed(String host, String configured) {
        if (configured == null) return false;
        for (String item : configured.split(",")) {
            String candidate =
                    item.trim().toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
            if (candidate.equals(host) || candidate.equals("*")) return true;
        }
        return false;
    }
}
