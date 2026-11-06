package com.astral.storage.http;

import com.astral.common.exception.BusinessException;
import com.astral.storage.config.StorageProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * CUSTOM_HTTP 渠道画像的严格校验器（设计 v1.1 §3）。
 *
 * <p>职责：把客户端提交的画像校验并归一化成可直接执行的有效画像——
 * 目标地址经 {@link ApprovedTargetPolicy} 精确匹配后固化为 {@code origin + path}；
 * Query/Header/Form 键值数量与长度受限；模板在创建时编译；响应条件、Pointer、单位、限额边界全部校验；
 * 序列化后不得超 16 KiB。任何不合法点都以 STORAGE034 拒绝，文案只带字段名与固定原因，不回显凭据值。</p>
 *
 * <p>凭据是否已配置不在此校验（见 {@link HttpSecretEnvelope#requireSecrets}），本类对凭据取值无感知。</p>
 */
@Component
public class HttpProfileValidator {

    /** 单个 Map（query / headers / fields）最多条目数 */
    public static final int MAX_MAP_ENTRIES = 16;

    /** Map 键最大长度 */
    public static final int MAX_KEY_LENGTH = 64;

    /** endpoint 最大长度 */
    public static final int MAX_ENDPOINT_LENGTH = 2048;

    /** 成功条件条数上限 */
    public static final int MAX_CONDITIONS = 8;

    /** JSON Pointer 最大长度 */
    public static final int MAX_POINTER_LENGTH = 128;

    /** JSON Pointer 最大层级 */
    public static final int MAX_POINTER_DEPTH = 12;

    /** 允许的输出 origin 数量上限 */
    public static final int MAX_ALLOWED_ORIGINS = 8;

    /** numericStringPointers 数量上限 */
    public static final int MAX_NUMERIC_STRING_POINTERS = 8;

    /** allowedMimes 数量上限 */
    public static final int MAX_ALLOWED_MIMES = 16;

    /** 单次尝试预算下限（毫秒） */
    public static final int MIN_ATTEMPT_MS = 1000;

    /** 单次尝试预算上限（毫秒） */
    public static final int MAX_ATTEMPT_MS = 60000;

    /** 预期值文本上限 */
    public static final int MAX_CONDITION_VALUE_LENGTH = 512;

    /**
     * 首发支持的公共图片 MIME（§11.3(5)：与本地 magic 校验器 {@link UploadImageValidator} 的可验证集合一致）。
     * image/gif、image/webp 暂不支持：本地无对应 magic 解析，画像放行会在正文校验阶段 STORAGE045 拒绝，形成死路；
     * 待补齐对应头校验后再放开。
     */
    public static final Set<String> SUPPORTED_MIMES = Set.of("image/png", "image/jpeg");

    private static final Pattern SAFE_FIELD_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Pattern SAFE_FORM_FIELD_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");
    private static final Pattern SAFE_FILENAME_LITERAL = Pattern.compile("^[A-Za-z0-9._-]*$");
    private static final Pattern PRESET_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    /** 禁止覆盖的请求头（小写）；另外禁止 proxy- / x-app- / sec- 前缀 */
    private static final Set<String> FORBIDDEN_HEADERS = Set.of(
            "host",
            "content-length",
            "transfer-encoding",
            "connection",
            "trailer",
            "expect",
            "te",
            "upgrade",
            "keep-alive",
            "accept-encoding",
            "content-type",
            "cookie",
            "satoken",
            "user-agent",
            "forwarded",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto",
            "x-real-ip",
            "proxy-connection",
            "proxy-authorization");

    private static final Set<String> FORBIDDEN_HEADER_PREFIXES = Set.of("proxy-", "x-app-", "sec-", "x-forwarded-");

    private static final Set<String> SIZE_UNITS =
            Set.of(HttpUploadProfile.SIZE_UNIT_BYTES, HttpUploadProfile.SIZE_UNIT_KB, HttpUploadProfile.SIZE_UNIT_KIB);

    private static final Set<String> TYPES =
            Set.of(HttpUploadProfile.TYPE_STRING, HttpUploadProfile.TYPE_NUMBER, HttpUploadProfile.TYPE_BOOLEAN);

    private final ApprovedTargetPolicy policy;

    private final ObjectMapper mapper;

    public HttpProfileValidator(ApprovedTargetPolicy policy, ObjectMapper mapper) {
        this.policy = policy;
        this.mapper = mapper;
    }

    /**
     * 校验并归一化画像。
     *
     * @param profile 客户端提交的画像
     * @return 归一化画像与命中的批准目标
     * @throws BusinessException STORAGE034：画像非法；STORAGE035：目标不在批准清单
     */
    public Validated validate(HttpUploadProfile profile) {
        if (profile == null) {
            throw invalid("profile", "不能为空");
        }
        if (profile.schemaVersion() == null || profile.schemaVersion() != HttpUploadProfile.CURRENT_SCHEMA_VERSION) {
            throw invalid("schemaVersion", "只支持 " + HttpUploadProfile.CURRENT_SCHEMA_VERSION);
        }
        if (profile.method() == null
                || !HttpUploadProfile.METHOD_POST.equalsIgnoreCase(
                        profile.method().trim())) {
            throw invalid("method", "只支持 POST");
        }
        String presetId = validatePreset(profile.presetId(), profile.presetVersion());
        Integer presetVersion = profile.presetVersion();
        if (profile.endpoint() == null || profile.endpoint().isBlank()) {
            throw invalid("endpoint", "不能为空");
        }
        if (profile.endpoint().length() > MAX_ENDPOINT_LENGTH) {
            throw invalid("endpoint", "长度超过 " + MAX_ENDPOINT_LENGTH);
        }
        StorageProperties.ApprovedTarget target =
                policy.require(profile.endpoint().trim());
        String endpoint = target.getOrigin() + target.getPath();
        Map<String, String> query = validateQuery(profile.query());
        Map<String, String> headers = validateHeaders(profile.headers());
        HttpUploadProfile.Auth auth = validateAuth(profile.auth(), query, headers);
        HttpUploadProfile.Body body = validateBody(profile.body(), auth);
        HttpUploadProfile.ResponseSpec response = validateResponse(profile.response(), target);
        HttpUploadProfile.Limits limits = validateLimits(profile.limits());
        HttpUploadProfile.Timeouts timeouts = validateTimeouts(profile.timeouts());
        HttpUploadProfile normalized = new HttpUploadProfile(
                HttpUploadProfile.CURRENT_SCHEMA_VERSION,
                presetId,
                presetVersion,
                endpoint,
                HttpUploadProfile.METHOD_POST,
                query,
                headers,
                auth,
                body,
                response,
                limits,
                timeouts);
        requireSerializedSize(normalized);
        return new Validated(normalized, target);
    }

    /** 校验通过后的有效画像与命中的批准目标（绑定 origin/path 的来源） */
    public record Validated(HttpUploadProfile profile, StorageProperties.ApprovedTarget target) {}

    /** JSON Pointer 校验：RFC 6901、≤128 字符、≤12 层、不支持通配/脚本/正则 */
    public static boolean isValidPointer(String pointer) {
        if (pointer == null || pointer.isEmpty() || pointer.length() > MAX_POINTER_LENGTH) {
            return false;
        }
        if (!pointer.startsWith("/")) {
            return false;
        }
        String[] tokens = pointer.substring(1).split("/", -1);
        if (tokens.length > MAX_POINTER_DEPTH) {
            return false;
        }
        for (String token : tokens) {
            if (token.isEmpty()) {
                return false;
            }
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    return false;
                }
                if (c == '*' || c == '?' || c == '[' || c == ']') {
                    return false;
                }
                if (c == '~'
                        && (i + 1 >= token.length() || (token.charAt(i + 1) != '0' && token.charAt(i + 1) != '1'))) {
                    return false;
                }
            }
            if (token.contains("..")) {
                return false;
            }
        }
        return true;
    }

    private String validatePreset(String presetId, Integer presetVersion) {
        if (presetId == null && presetVersion == null) {
            return null;
        }
        if (presetId == null || presetVersion == null) {
            throw invalid("preset", "presetId 与 presetVersion 必须成对出现");
        }
        if (!PRESET_ID.matcher(presetId).matches()) {
            throw invalid("presetId", "只允许 1–64 位字母/数字/下划线/点/冒号/短横线");
        }
        if (presetVersion < 1 || presetVersion > 100000) {
            throw invalid("presetVersion", "必须是正整数");
        }
        return presetId;
    }

    private Map<String, String> validateQuery(Map<String, String> query) {
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        if (query.size() > MAX_MAP_ENTRIES) {
            throw invalid("query", "最多 " + MAX_MAP_ENTRIES + " 项");
        }
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
                throw invalid("query", "参数名必须为 1–" + MAX_KEY_LENGTH + " 个字符");
            }
            checkNoControlChars(key, "query." + key);
            if (entry.getValue() == null) {
                throw invalid("query." + key, "模板不能为空");
            }
            HttpTemplateEngine.compile(entry.getValue(), "query." + key);
            normalized.put(key, entry.getValue());
        }
        return normalized;
    }

    private Map<String, String> validateHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        if (headers.size() > MAX_MAP_ENTRIES) {
            throw invalid("headers", "最多 " + MAX_MAP_ENTRIES + " 项");
        }
        Map<String, String> normalized = new TreeMap<>();
        Set<String> seenLower = new HashSet<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            HttpTemplateEngine.checkHeaderName(key);
            String lower = key.toLowerCase(Locale.ROOT);
            if (!seenLower.add(lower)) {
                throw invalid("headers", "Header 名大小写不敏感重复: " + key);
            }
            if (isForbiddenHeader(key)) {
                throw invalid("headers." + key, "不允许覆盖该请求头");
            }
            if (entry.getValue() == null) {
                throw invalid("headers." + key, "模板不能为空");
            }
            HttpTemplateEngine.CompiledTemplate compiled =
                    HttpTemplateEngine.compile(entry.getValue(), "headers." + key);
            for (String literal : compiled.literals()) {
                HttpTemplateEngine.checkHeaderValue(key, literal);
            }
            normalized.put(key, entry.getValue());
        }
        return normalized;
    }

    private HttpUploadProfile.Auth validateAuth(
            HttpUploadProfile.Auth auth, Map<String, String> query, Map<String, String> headers) {
        if (auth == null) {
            throw invalid("auth", "缺少认证配置");
        }
        String mode = auth.mode() == null ? "" : auth.mode().trim().toUpperCase(Locale.ROOT);
        switch (mode) {
            case HttpUploadProfile.AUTH_NONE -> throw invalid("auth", "本期不接受匿名认证模式 NONE");
            case HttpUploadProfile.AUTH_HEADER -> {
                requireNoBasicFields(auth);
                HttpTemplateEngine.checkHeaderName(auth.name());
                if (isForbiddenHeader(auth.name())) {
                    throw invalid("auth", "认证头不允许使用该请求头");
                }
                for (String key : headers.keySet()) {
                    if (key.equalsIgnoreCase(auth.name())) {
                        throw invalid("auth", "认证头与 headers 同名冲突: " + auth.name());
                    }
                }
                String valueTemplate = requireAuthTemplate(auth.valueTemplate());
                return new HttpUploadProfile.Auth(
                        HttpUploadProfile.AUTH_HEADER, auth.name(), valueTemplate, null, null);
            }
            case HttpUploadProfile.AUTH_QUERY -> {
                requireNoBasicFields(auth);
                validateAuthParamName(auth.name(), "auth");
                if (query.containsKey(auth.name())) {
                    throw invalid("auth", "认证参数与 query 同名冲突: " + auth.name());
                }
                String valueTemplate = requireAuthTemplate(auth.valueTemplate());
                return new HttpUploadProfile.Auth(HttpUploadProfile.AUTH_QUERY, auth.name(), valueTemplate, null, null);
            }
            case HttpUploadProfile.AUTH_FORM -> {
                requireNoBasicFields(auth);
                validateAuthParamName(auth.name(), "auth");
                String valueTemplate = requireAuthTemplate(auth.valueTemplate());
                return new HttpUploadProfile.Auth(HttpUploadProfile.AUTH_FORM, auth.name(), valueTemplate, null, null);
            }
            case HttpUploadProfile.AUTH_BASIC -> {
                if (auth.name() != null || auth.valueTemplate() != null) {
                    throw invalid("auth", "BASIC 认证不接受 name / valueTemplate");
                }
                if (!HttpSecretEnvelope.isValidSecretName(auth.usernameSecret())
                        || !HttpSecretEnvelope.isValidSecretName(auth.passwordSecret())) {
                    throw invalid("auth", "BASIC 认证的凭据名非法");
                }
                if (auth.usernameSecret().equals(auth.passwordSecret())) {
                    throw invalid("auth", "BASIC 用户名与口令必须是两个凭据");
                }
                for (String key : headers.keySet()) {
                    if ("authorization".equalsIgnoreCase(key)) {
                        throw invalid("auth", "BASIC 认证已占用 Authorization，不能再配置同名字段");
                    }
                }
                return new HttpUploadProfile.Auth(
                        HttpUploadProfile.AUTH_BASIC, null, null, auth.usernameSecret(), auth.passwordSecret());
            }
            default -> throw invalid("auth", "不支持的认证模式");
        }
    }

    private HttpUploadProfile.Body validateBody(HttpUploadProfile.Body body, HttpUploadProfile.Auth auth) {
        if (body == null) {
            throw invalid("body", "缺少正文配置");
        }
        if (body.mode() == null
                || !HttpUploadProfile.BODY_MULTIPART.equalsIgnoreCase(
                        body.mode().trim())) {
            throw invalid("body.mode", "只支持 MULTIPART");
        }
        if (body.fileField() == null
                || !SAFE_FIELD_NAME.matcher(body.fileField()).matches()) {
            throw invalid("body.fileField", "只允许 1–64 位字母/数字/下划线/短横线");
        }
        String fileNameTemplate = validateFileNameTemplate(body.fileNameTemplate());
        Map<String, String> fields = validateFields(body.fields(), body.fileField(), auth);
        return new HttpUploadProfile.Body(HttpUploadProfile.BODY_MULTIPART, body.fileField(), fileNameTemplate, fields);
    }

    private String validateFileNameTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw invalid("body.fileNameTemplate", "不能为空");
        }
        if (template.length() > 255) {
            throw invalid("body.fileNameTemplate", "长度超过 255");
        }
        HttpTemplateEngine.CompiledTemplate compiled = HttpTemplateEngine.compile(template, "body.fileNameTemplate");
        for (HttpTemplateEngine.Part part : compiled.parts()) {
            if (part instanceof HttpTemplateEngine.SecretRef) {
                throw invalid("body.fileNameTemplate", "不允许引用凭据");
            }
            if (part instanceof HttpTemplateEngine.VariableRef ref
                    && ref.variable() != HttpTemplateEngine.Variable.PUBLIC_ID
                    && ref.variable() != HttpTemplateEngine.Variable.EXTENSION) {
                throw invalid("body.fileNameTemplate", "只允许 ${publicId} 与 ${extension}");
            }
        }
        for (String literal : compiled.literals()) {
            if (!SAFE_FILENAME_LITERAL.matcher(literal).matches()) {
                throw invalid("body.fileNameTemplate", "只允许 ASCII 字母/数字/点/下划线/短横线");
            }
        }
        return template;
    }

    private Map<String, String> validateFields(
            Map<String, String> fields, String fileField, HttpUploadProfile.Auth auth) {
        if (fields == null || fields.isEmpty()) {
            return Map.of();
        }
        if (fields.size() > MAX_MAP_ENTRIES) {
            throw invalid("body.fields", "最多 " + MAX_MAP_ENTRIES + " 项");
        }
        Map<String, String> normalized = new TreeMap<>();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (key == null || !SAFE_FORM_FIELD_NAME.matcher(key).matches()) {
                throw invalid("body.fields", "字段名只允许 1–64 位字母/数字/下划线/点/短横线");
            }
            if (key.equals(fileField)) {
                throw invalid("body.fields." + key, "不能与文件字段同名");
            }
            if (HttpUploadProfile.AUTH_FORM.equals(auth.mode()) && key.equals(auth.name())) {
                throw invalid("body.fields." + key, "不能与认证字段同名");
            }
            if (entry.getValue() == null) {
                throw invalid("body.fields." + key, "模板不能为空");
            }
            HttpTemplateEngine.compile(entry.getValue(), "body.fields." + key);
            normalized.put(key, entry.getValue());
        }
        return normalized;
    }

    private HttpUploadProfile.ResponseSpec validateResponse(
            HttpUploadProfile.ResponseSpec spec, StorageProperties.ApprovedTarget target) {
        if (spec == null) {
            throw invalid("response", "缺少响应解析配置");
        }
        List<HttpUploadProfile.Condition> conditions = validateConditions(spec.success());
        String urlPointer = requirePointer(spec.urlPointer(), "response.urlPointer", true);
        String thumbPointer = requirePointer(spec.thumbPointer(), "response.thumbPointer", false);
        String idPointer = requirePointer(spec.idPointer(), "response.idPointer", false);
        String widthPointer = requirePointer(spec.widthPointer(), "response.widthPointer", false);
        String heightPointer = requirePointer(spec.heightPointer(), "response.heightPointer", false);
        String sizePointer = requirePointer(spec.sizePointer(), "response.sizePointer", false);
        String sizeUnit = null;
        if (sizePointer != null) {
            sizeUnit = spec.sizeUnit() == null ? null : spec.sizeUnit().trim().toUpperCase(Locale.ROOT);
            if (sizeUnit == null || !SIZE_UNITS.contains(sizeUnit)) {
                throw invalid("response.sizeUnit", "sizePointer 必须配 BYTES / KB / KIB");
            }
        } else if (spec.sizeUnit() != null) {
            throw invalid("response.sizeUnit", "声明了 sizeUnit 但缺少 sizePointer");
        }
        List<String> numericStringPointers = validatePointerList(
                spec.numericStringPointers(), "response.numericStringPointers", MAX_NUMERIC_STRING_POINTERS);
        List<String> allowedOrigins = validateAllowedOrigins(spec.allowedUrlOrigins(), target);
        return new HttpUploadProfile.ResponseSpec(
                conditions,
                urlPointer,
                thumbPointer,
                idPointer,
                sizePointer,
                sizeUnit,
                widthPointer,
                heightPointer,
                numericStringPointers,
                allowedOrigins);
    }

    private List<HttpUploadProfile.Condition> validateConditions(List<HttpUploadProfile.Condition> success) {
        if (success == null || success.isEmpty()) {
            throw invalid("response.success", "需要 1–" + MAX_CONDITIONS + " 条成功条件");
        }
        if (success.size() > MAX_CONDITIONS) {
            throw invalid("response.success", "需要 1–" + MAX_CONDITIONS + " 条成功条件");
        }
        List<HttpUploadProfile.Condition> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (HttpUploadProfile.Condition condition : success) {
            if (condition == null) {
                throw invalid("response.success", "条件不能为空");
            }
            if (!isValidPointer(condition.pointer())) {
                throw invalid("response.success.pointer", "JSON Pointer 非法");
            }
            if (!seen.add(condition.pointer() + "|" + (condition.op() == null ? "" : condition.op()))) {
                throw invalid("response.success", "存在重复条件");
            }
            String op = condition.op() == null ? "" : condition.op().trim().toUpperCase(Locale.ROOT);
            if (HttpUploadProfile.OP_EXISTS.equals(op)) {
                if (condition.type() != null || condition.value() != null) {
                    throw invalid("response.success", "EXISTS 条件不接受 type / value");
                }
                normalized.add(new HttpUploadProfile.Condition(condition.pointer(), op, null, null));
                continue;
            }
            if (!HttpUploadProfile.OP_EQ.equals(op)) {
                throw invalid("response.success", "条件操作只支持 EQ / EXISTS");
            }
            String type =
                    condition.type() == null ? "" : condition.type().trim().toUpperCase(Locale.ROOT);
            if (!TYPES.contains(type)) {
                throw invalid("response.success", "EQ 条件必须声明 STRING / NUMBER / BOOLEAN");
            }
            String value = condition.value();
            if (value == null) {
                throw invalid("response.success", "EQ 条件缺少预期值");
            }
            String expected = normalizeExpected(type, value);
            normalized.add(new HttpUploadProfile.Condition(condition.pointer(), op, type, expected));
        }
        return List.copyOf(normalized);
    }

    private String normalizeExpected(String type, String value) {
        return switch (type) {
            case HttpUploadProfile.TYPE_STRING -> {
                if (value.length() > MAX_CONDITION_VALUE_LENGTH) {
                    throw invalid("response.success", "字符串预期值超过 " + MAX_CONDITION_VALUE_LENGTH);
                }
                checkNoControlChars(value, "response.success");
                yield value;
            }
            case HttpUploadProfile.TYPE_NUMBER -> {
                String trimmed = value.trim();
                if (trimmed.isEmpty() || trimmed.length() > 32) {
                    throw invalid("response.success", "数值预期值非法");
                }
                try {
                    new BigDecimal(trimmed);
                } catch (NumberFormatException e) {
                    throw invalid("response.success", "数值预期值非法");
                }
                yield trimmed;
            }
            default -> {
                String trimmed = value.trim();
                if (!"true".equalsIgnoreCase(trimmed) && !"false".equalsIgnoreCase(trimmed)) {
                    throw invalid("response.success", "布尔预期值只能是 true / false");
                }
                yield trimmed.toLowerCase(Locale.ROOT);
            }
        };
    }

    private String requirePointer(String pointer, String field, boolean required) {
        if (pointer == null || pointer.isBlank()) {
            if (required) {
                throw invalid(field, "必填");
            }
            return null;
        }
        if (!isValidPointer(pointer)) {
            throw invalid(
                    field, "JSON Pointer 非法（须以 / 开头、≤" + MAX_POINTER_LENGTH + " 字符、≤" + MAX_POINTER_DEPTH + " 层）");
        }
        return pointer;
    }

    private List<String> validatePointerList(List<String> pointers, String field, int max) {
        if (pointers == null || pointers.isEmpty()) {
            return List.of();
        }
        if (pointers.size() > max) {
            throw invalid(field, "最多 " + max + " 个");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String pointer : pointers) {
            if (!isValidPointer(pointer)) {
                throw invalid(field, "JSON Pointer 非法");
            }
            if (!unique.add(pointer)) {
                throw invalid(field, "Pointer 重复");
            }
        }
        return List.copyOf(unique);
    }

    private List<String> validateAllowedOrigins(List<String> origins, StorageProperties.ApprovedTarget target) {
        if (origins == null || origins.isEmpty()) {
            return List.of();
        }
        if (origins.size() > MAX_ALLOWED_ORIGINS) {
            throw invalid("response.allowedUrlOrigins", "最多 " + MAX_ALLOWED_ORIGINS + " 个");
        }
        List<String> approved = target.getUrlOrigins() == null ? List.of() : target.getUrlOrigins();
        Set<String> unique = new LinkedHashSet<>();
        for (String origin : origins) {
            String normalized;
            try {
                normalized = ApprovedTargetPolicy.normalizeOrigin(origin, true);
            } catch (IllegalArgumentException e) {
                throw invalid("response.allowedUrlOrigins", "必须是精确 HTTPS origin");
            }
            if (!approved.contains(normalized)) {
                throw invalid("response.allowedUrlOrigins", "必须是该批准目标输出 origin 的子集");
            }
            unique.add(normalized);
        }
        return List.copyOf(unique);
    }

    private HttpUploadProfile.Limits validateLimits(HttpUploadProfile.Limits limits) {
        long maxBytes = HttpUploadProfile.HARD_MAX_BYTES;
        if (limits != null && limits.maxBytes() != null) {
            maxBytes = limits.maxBytes();
            if (maxBytes < 1 || maxBytes > HttpUploadProfile.HARD_MAX_BYTES) {
                throw invalid("limits.maxBytes", "必须在 1–" + HttpUploadProfile.HARD_MAX_BYTES + " 之间");
            }
        }
        List<String> mimes = limits == null ? null : limits.allowedMimes();
        if (mimes == null || mimes.isEmpty()) {
            throw invalid("limits.allowedMimes", "必须至少声明一个 MIME");
        }
        if (mimes.size() > MAX_ALLOWED_MIMES) {
            throw invalid("limits.allowedMimes", "最多 " + MAX_ALLOWED_MIMES + " 个");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String mime : mimes) {
            String normalized = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
            if (!SUPPORTED_MIMES.contains(normalized)) {
                throw invalid("limits.allowedMimes", "只支持 image/png、image/jpeg、image/gif、image/webp");
            }
            unique.add(normalized);
        }
        return new HttpUploadProfile.Limits(maxBytes, List.copyOf(unique));
    }

    private HttpUploadProfile.Timeouts validateTimeouts(HttpUploadProfile.Timeouts timeouts) {
        int attemptMs = HttpUploadProfile.DEFAULT_ATTEMPT_MS;
        if (timeouts != null && timeouts.attemptMs() != null) {
            attemptMs = timeouts.attemptMs();
            if (attemptMs < MIN_ATTEMPT_MS || attemptMs > MAX_ATTEMPT_MS) {
                throw invalid("timeouts.attemptMs", "必须在 " + MIN_ATTEMPT_MS + "–" + MAX_ATTEMPT_MS + " 毫秒之间");
            }
        }
        return new HttpUploadProfile.Timeouts(attemptMs);
    }

    private void requireSerializedSize(HttpUploadProfile profile) {
        String json;
        try {
            json = mapper.writeValueAsString(profile);
        } catch (RuntimeException e) {
            throw invalid("profile", "序列化失败");
        }
        if (HttpTemplateEngine.utf8Length(json) > HttpUploadProfile.MAX_SERIALIZED_BYTES) {
            throw invalid("profile", "序列化后超过 " + HttpUploadProfile.MAX_SERIALIZED_BYTES + " 字节");
        }
    }

    private static void requireNoBasicFields(HttpUploadProfile.Auth auth) {
        if (auth.usernameSecret() != null || auth.passwordSecret() != null) {
            throw invalid("auth", "该认证模式不接受 usernameSecret / passwordSecret");
        }
    }

    private static String requireAuthTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw invalid("auth", "认证模板不能为空");
        }
        HttpTemplateEngine.compile(template, "auth");
        return template;
    }

    private static void validateAuthParamName(String name, String field) {
        if (name == null || name.isBlank() || name.length() > MAX_KEY_LENGTH) {
            throw invalid(field, "参数名必须为 1–" + MAX_KEY_LENGTH + " 个字符");
        }
        checkNoControlChars(name, field);
    }

    private static boolean isForbiddenHeader(String name) {
        if (name == null) {
            return true;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_HEADERS.contains(lower)) {
            return true;
        }
        for (String prefix : FORBIDDEN_HEADER_PREFIXES) {
            if (lower.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static void checkNoControlChars(String value, String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw invalid(field, "包含控制字符");
            }
        }
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException("STORAGE034", field + " " + reason);
    }
}
