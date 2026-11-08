package com.astral.storage.http;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * CUSTOM_HTTP 上游响应解析器（设计 v1.1 §3.4 / §12）。
 *
 * <p>规则：HTTP 状态必须 2xx；JSON 严格解析（{@link StrictJson}）；全部成功条件按类型化精确匹配；
 * 直链必须命中批准目标并用 {@link ApprovedTargetPolicy#requireOutputUrl} 归一化，且落入画像声明的
 * output origins；尺寸/宽高按声明单位有界换算，非法只丢该字段；一旦发现凭据材料（原值/百分号编码/
 * Base64/Base64URL/Hex）被上游反射，整条响应隔离，不返回任何可用值。</p>
 *
 * <p>本类不记录上游正文，异常只带固定安全码；上游错误内容永不进入日志或接口。</p>
 */
@Component
public class HttpResponseParser {

    /** 解析结论 */
    public enum Kind {
        /** 通过：各字段可用 */
        OK,
        /** 响应不可用（状态码/JSON/成功条件/缺直链） */
        PARSE_FAILED,
        /** 直链不在批准范围 */
        URL_REJECTED,
        /** 上游反射了凭据材料，整条隔离 */
        CREDENTIAL_REFLECTION
    }

    /** 解析结果；{@code safeCode} 是固定枚举文案，可安全落日志 */
    public record ParsedResult(
            Kind kind,
            int httpStatus,
            String safeCode,
            String url,
            String thumbUrl,
            String remoteId,
            Long remoteSizeBytes,
            Integer width,
            Integer height,
            List<String> droppedFields) {

        public boolean ok() {
            return kind == Kind.OK;
        }
    }

    /** 凭据反射检测的最小长度：过短的凭据值会与普通文本碰撞 */
    public static final int MIN_REFLECTION_LENGTH = 8;

    /** 反射检测纳入的 Hex 编码长度上限（避免超长值放大扫描成本） */
    private static final int MAX_HEX_SOURCE_BYTES = 512;

    /** 远端 id 文本上限 */
    private static final int MAX_REMOTE_ID_LENGTH = 200;

    /** 宽高像素上限 */
    private static final int MAX_PIXEL = 1000000;

    private final ApprovedTargetPolicy policy;

    public HttpResponseParser(ApprovedTargetPolicy policy) {
        this.policy = policy;
    }

    /**
     * 解析上游响应。
     *
     * @param httpStatus 上游 HTTP 状态
     * @param body       上游正文（仅在内存中解析，不落日志）
     * @param profile    有效画像（含响应解析规则与允许的输出 origin）
     * @param target     命中的批准目标
     * @param secrets    已配置凭据（名称 → 明文），仅用于反射检测，不写出
     * @return 解析结论
     * @throws BusinessException STORAGE034：调用方未提供有效画像
     */
    public ParsedResult parse(
            int httpStatus,
            String body,
            HttpUploadProfile profile,
            StorageProperties.ApprovedTarget target,
            Map<String, String> secrets) {
        if (profile == null || profile.response() == null) {
            throw new BusinessException("STORAGE034", "响应解析缺少渠道画像");
        }
        HttpUploadProfile.ResponseSpec spec = profile.response();
        if (httpStatus < 200 || httpStatus > 299) {
            return failure(Kind.PARSE_FAILED, httpStatus, "UPSTREAM_HTTP_STATUS");
        }
        if (body == null || body.isBlank()) {
            return failure(Kind.PARSE_FAILED, httpStatus, "EMPTY_RESPONSE");
        }
        Object root;
        try {
            root = StrictJson.readTree(body, StrictJson.MAX_RESPONSE_BYTES);
        } catch (StrictJson.StrictJsonException e) {
            return failure(Kind.PARSE_FAILED, httpStatus, "INVALID_JSON");
        }
        for (HttpUploadProfile.Condition condition : spec.success()) {
            if (!matches(root, condition)) {
                return failure(Kind.PARSE_FAILED, httpStatus, "SUCCESS_CONDITION_FAILED");
            }
        }
        String rawUrl = StrictJson.textAt(root, spec.urlPointer());
        if (rawUrl == null || rawUrl.isBlank()) {
            return failure(Kind.PARSE_FAILED, httpStatus, "MISSING_URL");
        }
        String url;
        try {
            url = policy.requireOutputUrl(rawUrl, target);
        } catch (BusinessException e) {
            return failure(Kind.URL_REJECTED, httpStatus, "URL_NOT_APPROVED");
        }
        if (!originAllowed(url, spec.allowedUrlOrigins())) {
            return failure(Kind.URL_REJECTED, httpStatus, "URL_ORIGIN_NOT_ALLOWED");
        }
        List<String> dropped = new ArrayList<>();
        String thumbUrl = optionalUrl(root, spec.thumbPointer(), target, spec.allowedUrlOrigins(), "thumbUrl", dropped);
        String remoteId = optionalText(root, spec.idPointer(), MAX_REMOTE_ID_LENGTH, "remoteId", dropped);
        Long remoteSizeBytes = optionalSize(root, spec, dropped);
        Integer width = optionalPixels(root, spec.widthPointer(), "width", dropped);
        Integer height = optionalPixels(root, spec.heightPointer(), "height", dropped);
        if (reflectsCredentialMaterial(body, profile, secrets)
                || reflectsCredentialMaterial(url, profile, Map.of())
                || reflectsCredentialMaterial(thumbUrl, profile, Map.of())
                || reflectsCredentialMaterial(remoteId, profile, Map.of())) {
            return failure(Kind.CREDENTIAL_REFLECTION, httpStatus, "CREDENTIAL_REFLECTION");
        }
        return new ParsedResult(
                Kind.OK,
                httpStatus,
                "OK",
                url,
                thumbUrl,
                remoteId,
                remoteSizeBytes,
                width,
                height,
                List.copyOf(dropped));
    }

    private static ParsedResult failure(Kind kind, int httpStatus, String safeCode) {
        return new ParsedResult(kind, httpStatus, safeCode, null, null, null, null, null, null, List.of());
    }

    private static boolean matches(Object root, HttpUploadProfile.Condition condition) {
        Object value = StrictJson.pointer(root, condition.pointer());
        if (HttpUploadProfile.OP_EXISTS.equals(condition.op())) {
            return value != StrictJson.MISSING && value != null;
        }
        if (value == StrictJson.MISSING || value == null) {
            return false;
        }
        return switch (condition.type()) {
            case HttpUploadProfile.TYPE_STRING -> value instanceof String text && text.equals(condition.value());
            case HttpUploadProfile.TYPE_NUMBER -> matchesNumber(value, condition.value());
            case HttpUploadProfile.TYPE_BOOLEAN ->
                value instanceof Boolean flag && flag == Boolean.parseBoolean(condition.value());
            default -> false;
        };
    }

    private static boolean matchesNumber(Object value, String expected) {
        if (!(value instanceof Number number)) {
            return false;
        }
        try {
            return new BigDecimal(number.toString()).compareTo(new BigDecimal(expected)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String optionalUrl(
            Object root,
            String pointer,
            StorageProperties.ApprovedTarget target,
            List<String> allowedOrigins,
            String field,
            List<String> dropped) {
        if (pointer == null) {
            return null;
        }
        String raw = StrictJson.textAt(root, pointer);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            String normalized = policy.requireOutputUrl(raw, target);
            if (!originAllowed(normalized, allowedOrigins)) {
                dropped.add(field);
                return null;
            }
            return normalized;
        } catch (BusinessException e) {
            dropped.add(field);
            return null;
        }
    }

    private static String optionalText(Object root, String pointer, int maxLength, String field, List<String> dropped) {
        if (pointer == null) {
            return null;
        }
        String text = StrictJson.textAt(root, pointer);
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.length() > maxLength) {
            dropped.add(field);
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                dropped.add(field);
                return null;
            }
        }
        return text;
    }

    private static Long optionalSize(Object root, HttpUploadProfile.ResponseSpec spec, List<String> dropped) {
        if (spec.sizePointer() == null || spec.sizeUnit() == null) {
            return null;
        }
        BigDecimal raw = decimalAt(root, spec.sizePointer(), spec.numericStringPointers());
        Long bytes = convertSize(raw, spec.sizeUnit());
        if (bytes == null) {
            dropped.add("sizeBytes");
        }
        return bytes;
    }

    private static Integer optionalPixels(Object root, String pointer, String field, List<String> dropped) {
        if (pointer == null) {
            return null;
        }
        BigDecimal raw = decimalAt(root, pointer, List.of());
        if (raw == null) {
            return null;
        }
        try {
            int pixel = raw.intValueExact();
            if (pixel < 0 || pixel > MAX_PIXEL) {
                dropped.add(field);
                return null;
            }
            return pixel;
        } catch (ArithmeticException e) {
            dropped.add(field);
            return null;
        }
    }

    /**
     * 按声明的数值类型取值：默认只接受 JSON 数字；被列入 numericStringPointers 的字段额外接受数字字符串。
     */
    private static BigDecimal decimalAt(Object root, String pointer, List<String> numericStringPointers) {
        Object value = StrictJson.pointer(root, pointer);
        if (value == StrictJson.MISSING || value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (value instanceof String text && numericStringPointers != null && numericStringPointers.contains(pointer)) {
            String trimmed = text.trim();
            if (trimmed.isEmpty() || trimmed.length() > StrictJson.MAX_NUMBER_TEXT_LENGTH) {
                return null;
            }
            try {
                return new BigDecimal(trimmed);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static Long convertSize(BigDecimal raw, String unit) {
        if (raw == null || raw.signum() < 0) {
            return null;
        }
        BigDecimal factor = switch (unit) {
            case HttpUploadProfile.SIZE_UNIT_KB -> BigDecimal.valueOf(1000L);
            case HttpUploadProfile.SIZE_UNIT_KIB -> BigDecimal.valueOf(1024L);
            default -> BigDecimal.ONE;
        };
        BigDecimal bytes = raw.multiply(factor);
        BigInteger exact;
        try {
            exact = bytes.toBigIntegerExact();
        } catch (ArithmeticException e) {
            return null;
        }
        if (exact.signum() < 0 || exact.bitLength() > 63) {
            return null;
        }
        return exact.longValue();
    }

    private static boolean originAllowed(String url, List<String> allowedOrigins) {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            return true;
        }
        int pathIndex = url.indexOf('/', "https://".length());
        String origin = pathIndex < 0 ? url : url.substring(0, pathIndex);
        return allowedOrigins.contains(origin);
    }

    /**
     * 凭据反射检测：原值、百分号编码、Base64、Base64URL、Hex，以及 BASIC 的 base64(user:password)。
     */
    private static boolean reflectsCredentialMaterial(
            String text, HttpUploadProfile profile, Map<String, String> secrets) {
        if (text == null || text.isEmpty() || secrets == null || secrets.isEmpty()) {
            return false;
        }
        Set<String> sensitive = new HashSet<>();
        Set<String> hexNeedles = new HashSet<>();
        for (String value : secrets.values()) {
            if (value == null || value.length() < MIN_REFLECTION_LENGTH) {
                continue;
            }
            sensitive.add(value);
            sensitive.add(HttpTemplateEngine.encodeQueryComponent(value));
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            sensitive.add(Base64.getEncoder().encodeToString(bytes));
            sensitive.add(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
            if (bytes.length <= MAX_HEX_SOURCE_BYTES) {
                hexNeedles.add(toHex(bytes));
            }
        }
        HttpUploadProfile.Auth auth = profile.auth();
        if (auth != null && HttpUploadProfile.AUTH_BASIC.equals(auth.mode())) {
            String username = secrets.get(auth.usernameSecret());
            String password = secrets.get(auth.passwordSecret());
            if (username != null && password != null && password.length() >= MIN_REFLECTION_LENGTH) {
                String raw = username + ":" + password;
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                sensitive.add(encoded);
                sensitive.add("Basic " + encoded);
                sensitive.add("basic " + encoded);
            }
        }
        for (String needle : sensitive) {
            if (needle.length() >= MIN_REFLECTION_LENGTH && text.contains(needle)) {
                return true;
            }
        }
        if (!hexNeedles.isEmpty()) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String needle : hexNeedles) {
                if (lower.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }
}
