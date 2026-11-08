package com.astral.storage.http;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import jakarta.annotation.PostConstruct;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 部署侧批准目标清单（只读）。
 *
 * <p>设计约束（v1.1 §4.3）：管理接口不能扩大清单；每项精确给出 HTTPS origin、上传路径和允许的
 * 输出 URL origins；空清单意味着不能出站。禁止通配域名、IP 字面量、非 443 端口、userinfo、
 * fragment、动态 host；统一小写域名/IDNA/默认端口后再精确匹配。</p>
 *
 * <p>本类只做「批准范围」判定，不负责 DNS/IP 固定；解析后的地址仍须由 HTTP 执行器按
 * 公网 IPv4 白名单校验（见 {@code PublicIpDnsResolver}）。</p>
 */
@Slf4j
@Component
public class ApprovedTargetPolicy {

    private final StorageProperties properties;

    /** 规范化 origin → 该 origin 下的批准路径 */
    private final Map<String, Map<String, StorageProperties.ApprovedTarget>> targets = new LinkedHashMap<>();

    /** 全局允许的输出 URL origin 并集 */
    private final Set<String> allowedOutputOrigins = new LinkedHashSet<>();

    public ApprovedTargetPolicy(StorageProperties properties) {
        this.properties = properties;
    }

    /**
     * 启动时校验批准清单：开启直连渠道后，任何一条不合法的批准项都必须让应用启动失败，
     * 而不是把错误留到运行时被一个畸形 URL 绕过。
     */
    @PostConstruct
    void parseApprovedTargets() {
        StorageProperties.CustomHttp config = properties.getCustomHttp();
        if (config == null) {
            return;
        }
        for (StorageProperties.ApprovedTarget raw : config.getApprovedTargets()) {
            if (raw == null) {
                continue;
            }
            String origin = normalizeOrigin(raw.getOrigin(), true);
            String path = normalizePath(raw.getPath());
            List<String> urlOrigins = new ArrayList<>();
            for (String urlOrigin : raw.getUrlOrigins()) {
                urlOrigins.add(normalizeOrigin(urlOrigin, false));
            }
            StorageProperties.ApprovedTarget normalized = new StorageProperties.ApprovedTarget();
            normalized.setOrigin(origin);
            normalized.setPath(path);
            normalized.setUrlOrigins(List.copyOf(urlOrigins));
            targets.computeIfAbsent(origin, key -> new LinkedHashMap<>()).put(path, normalized);
            allowedOutputOrigins.addAll(urlOrigins);
        }
        if (config.isEnabled() && targets.isEmpty()) {
            throw new IllegalStateException("astral.plugins.storage.custom-http.enabled=true 但没有配置任何 approved-targets");
        }
        log.info("CUSTOM_HTTP 批准目标: enabled={} origins={}", config.isEnabled(), targets.keySet());
    }

    /** 直连渠道总开关（关闭时不得发起任何上游请求） */
    public boolean enabled() {
        StorageProperties.CustomHttp config = properties.getCustomHttp();
        return config != null && config.isEnabled();
    }

    /** 全局允许的输出 URL origin 并集（空集即不允许任何输出 URL） */
    public Set<String> allowedOutputOrigins() {
        return Collections.unmodifiableSet(allowedOutputOrigins);
    }

    /**
     * 校验上传地址是否命中批准清单。
     *
     * @param endpoint 画像里的 endpoint
     * @return 命中的批准项
     * @throws BusinessException STORAGE035：不在清单内或格式非法
     */
    public StorageProperties.ApprovedTarget require(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new BusinessException("STORAGE035");
        }
        URI uri;
        try {
            uri = new URI(endpoint);
        } catch (URISyntaxException e) {
            throw new BusinessException("STORAGE035");
        }
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException("STORAGE035");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
            throw new BusinessException("STORAGE035");
        }
        String origin = normalizeOrigin(uri.getScheme() + "://" + uri.getRawAuthority(), true);
        String path = normalizePath(uri.getRawPath());
        Map<String, StorageProperties.ApprovedTarget> byPath = targets.get(origin);
        if (byPath == null) {
            throw new BusinessException("STORAGE035");
        }
        StorageProperties.ApprovedTarget target = byPath.get(path);
        if (target == null) {
            throw new BusinessException("STORAGE035");
        }
        return target;
    }

    /**
     * 校验上游回传的直链是否属于该批准目标的输出 origin 集合。
     *
     * @param url    上游返回的 URL
     * @param target 命中的批准目标
     * @return 规范化后的 URL（origin + path，无 Query）
     * @throws BusinessException STORAGE035：不合格
     */
    public String requireOutputUrl(String url, StorageProperties.ApprovedTarget target) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            throw new BusinessException("STORAGE035");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new BusinessException("STORAGE035");
        }
        if (!uri.isAbsolute() || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new BusinessException("STORAGE035");
        }
        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null) {
            throw new BusinessException("STORAGE035");
        }
        String origin = normalizeOrigin(uri.getScheme() + "://" + uri.getRawAuthority(), false);
        if (!target.getUrlOrigins().contains(origin)) {
            throw new BusinessException("STORAGE035");
        }
        String path = normalizePath(uri.getRawPath());
        return origin + path;
    }

    /**
     * 规范化 origin：HTTPS、无 userinfo/fragment/query、端口必须是默认 443、域名不得是 IP 字面量或通配符。
     *
     * @param value          原始值
     * @param requireNoPath  是否要求路径为空或 "/"
     * @return 小写且 IDNA 归一后的 origin
     */
    public static String normalizeOrigin(String value, boolean requireNoPath) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException("origin 不能为空或过长");
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("origin 不是合法 URI");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("origin 必须是 https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("origin 不允许 userinfo");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("origin 不允许 Query 或 fragment");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("origin 只允许默认端口 443");
        }
        if (requireNoPath) {
            String path = uri.getRawPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new IllegalArgumentException("origin 不允许带路径");
            }
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("origin 缺少主机名");
        }
        if (host.endsWith(".")) {
            throw new IllegalArgumentException("origin 不允许尾点");
        }
        if (host.contains("*")) {
            throw new IllegalArgumentException("origin 不允许通配符");
        }
        if (host.startsWith("[") || host.contains(":")) {
            throw new IllegalArgumentException("origin 不允许 IPv6 字面量");
        }
        if (isIpv4Literal(host)) {
            throw new IllegalArgumentException("origin 不允许 IP 字面量");
        }
        String ascii;
        try {
            ascii = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("origin 主机名 IDNA 归一失败");
        }
        return "https://" + ascii.toLowerCase(Locale.ROOT);
    }

    /**
     * 规范化上传路径：必须以 / 开头，不允许 Query/fragment、反斜杠、百分号编码的分隔符或 ..
     *
     * @param path 原始路径
     * @return 规范化路径
     */
    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path 必须以 / 开头");
        }
        if (path.length() > 512) {
            throw new IllegalArgumentException("path 过长");
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("?") || lower.contains("#") || path.contains("\\")) {
            throw new IllegalArgumentException("path 不允许 Query、fragment 或反斜杠");
        }
        if (lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e%2e")) {
            throw new IllegalArgumentException("path 不允许编码过的分隔符或上级目录");
        }
        for (String segment : path.substring(1).split("/", -1)) {
            if ("..".equals(segment) || ".".equals(segment)) {
                throw new IllegalArgumentException("path 不允许上级或当前目录段");
            }
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("path 不允许空路径段");
            }
        }
        return path;
    }

    private static boolean isIpv4Literal(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            for (int i = 0; i < part.length(); i++) {
                if (!Character.isDigit(part.charAt(i))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }
}
