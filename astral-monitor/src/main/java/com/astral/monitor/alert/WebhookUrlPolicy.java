package com.astral.monitor.alert;

import com.astral.common.http.NetworkAddresses;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 告警 Webhook 出站地址策略。
 *
 * <p>WEBHOOK 渠道由管理员在后台填写 URL，属于典型的服务端出站请求入口：
 * 若不加限制，普通告警编辑权限即可把请求指向回环、内网、云元数据或任意第三方地址（SSRF）。
 * 本策略同时执行三层校验：</p>
 * <ol>
 *   <li><b>scheme + origin 白名单</b>：只允许部署侧批准的 origin（默认钉钉/飞书/企业微信官方域名），
 *       路径与查询串允许（机器人 token 在 query 中），用户信息、fragment、非 http(s) 一律拒绝；</li>
 *   <li><b>DNS 解析</b>：解析出的每个地址都必须是公网地址；回环/私网/链路本地/组播/全零/
 *       IPv6 唯一本地地址全部拒绝，云元数据地址即使开启私网也不放行；</li>
 *   <li><b>发送侧复检</b>：每次投递前重新校验，避免只信保存时校验而被 DNS 变化绕过。</li>
 * </ol>
 *
 * <p>发送使用 {@link #approve(URI)} 的已校验地址固定实际连接，保留原 hostname/TLS 校验。</p>
 */
@Slf4j
@Component
public class WebhookUrlPolicy {

    /** 云元数据地址/主机：任何情况下都不允许 */
    private static final Set<String> METADATA_HOSTS =
            Set.of("169.254.169.254", "metadata.google.internal", "100.100.100.200", "fd00:ec2::254");

    private final Set<String> allowedOrigins;
    private final boolean allowPrivateNetwork;
    private final AddressResolver resolver;

    public WebhookUrlPolicy(
            @Value("${astral.monitor.alert.webhook.allowed-origins:}") String allowedOrigins,
            @Value("${astral.monitor.alert.webhook.allow-private-network:false}") boolean allowPrivateNetwork) {
        this(allowedOrigins, allowPrivateNetwork, InetAddress::getAllByName);
    }

    WebhookUrlPolicy(String allowedOrigins, boolean allowPrivateNetwork, AddressResolver resolver) {
        this.allowedOrigins = parseAllowedOrigins(allowedOrigins);
        this.allowPrivateNetwork = allowPrivateNetwork;
        this.resolver = resolver;
    }

    /**
     * 校验 Webhook URL，返回规范化后的 URI。
     *
     * @throws IllegalArgumentException 校验不通过，message 可直接展示给管理员
     */
    public URI validate(String rawUrl) {
        URI uri = parseTarget(rawUrl);
        rejectPrivateAddresses(normalizeHost(uri.getHost()));
        return uri;
    }

    public InetAddress[] approve(URI target) {
        URI uri = parseTarget(target.toString());
        return rejectPrivateAddresses(normalizeHost(uri.getHost()));
    }

    private URI parseTarget(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("WEBHOOK 渠道缺少 url");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Webhook URL 格式非法");
        }
        if (uri.isOpaque() || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Webhook URL 必须包含合法主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Webhook URL 不允许携带用户信息");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("Webhook URL 不允许包含 fragment");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("Webhook 仅支持 http/https");
        }
        String host = normalizeHost(uri.getHost());
        String origin = originOf(scheme, host, uri.getPort());
        if (!allowedOrigins.contains(origin)) {
            throw new IllegalArgumentException(
                    "Webhook origin 未获服务端批准：" + origin + "（由 astral.monitor.alert.webhook.allowed-origins 配置）");
        }
        if (METADATA_HOSTS.contains(host)) {
            throw new IllegalArgumentException("Webhook 不允许指向云元数据地址");
        }
        return uri;
    }

    /** 返回允许的 origin 列表（用于启动日志/测试断言） */
    public Set<String> allowedOrigins() {
        return Set.copyOf(allowedOrigins);
    }

    private InetAddress[] rejectPrivateAddresses(String host) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Webhook 主机无法解析：" + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("Webhook 主机无法解析：" + host);
        }
        for (InetAddress address : addresses) {
            String literal = address.getHostAddress();
            if (NetworkAddresses.isMetadata(address)) {
                throw new IllegalArgumentException("Webhook 不允许指向云元数据地址");
            }
            if (!allowPrivateNetwork && !NetworkAddresses.isPublic(address)) {
                throw new IllegalArgumentException("Webhook 不允许指向私网/回环/链路本地地址：" + literal);
            }
        }
        return addresses.clone();
    }

    private static Set<String> parseAllowedOrigins(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String item : raw.split(",")) {
            String candidate = item.trim();
            if (candidate.isEmpty()) {
                continue;
            }
            try {
                URI uri = URI.create(candidate);
                String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
                if ((!"https".equals(scheme) && !"http".equals(scheme)) || uri.getHost() == null) {
                    log.warn("[alert] 忽略非法 webhook origin 配置: {}", candidate);
                    continue;
                }
                if (uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null) {
                    log.warn("[alert] 忽略带用户信息/query/fragment 的 webhook origin 配置: {}", candidate);
                    continue;
                }
                String path = uri.getPath();
                if (path != null && !path.isEmpty() && !"/".equals(path)) {
                    log.warn("[alert] 忽略带路径的 webhook origin 配置（应只配置 scheme://host[:port]）: {}", candidate);
                    continue;
                }
                result.add(originOf(scheme, normalizeHost(uri.getHost()), uri.getPort()));
            } catch (Exception e) {
                log.warn("[alert] 忽略无法解析的 webhook origin 配置: {}", candidate);
            }
        }
        return Set.copyOf(result);
    }

    private static String originOf(String scheme, String host, int port) {
        int effectivePort = port;
        if (effectivePort < 0) {
            effectivePort = "https".equals(scheme) ? 443 : 80;
        }
        String hostPart = host.contains(":") ? "[" + host + "]" : host;
        return scheme + "://" + hostPart + ":" + effectivePort;
    }

    private static String normalizeHost(String host) {
        String normalized = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    @FunctionalInterface
    interface AddressResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }
}
