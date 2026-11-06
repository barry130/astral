package com.astral.storage.http;

import com.astral.common.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 受限模板编译器与渲染器（设计 v1.1 §3.3）。
 *
 * <p>只支持 {@code ${secret:name}} 与六个固定变量，不支持表达式、环境变量、循环或任意函数。
 * 编译期拒绝未知变量与非法凭据名；渲染期拒绝控制字符、超长结果，并对 Query 键值各编码一次。</p>
 *
 * <p>本类同时提供发送链所需的纯函数渲染入口（{@link #buildUrl}、{@link #renderHeaders}、
 * {@link #renderFormFields}），避免执行链另写一套编码逻辑而与校验器发散。</p>
 */
public final class HttpTemplateEngine {

    /** 单个模板原文上限（UTF-8 字节） */
    public static final int MAX_TEMPLATE_BYTES = 1024;

    /** 单个渲染结果上限（UTF-8 字节） */
    public static final int MAX_RENDERED_VALUE_BYTES = 4096;

    /** 单个容器（Query / Header / Form）渲染结果合计上限（UTF-8 字节） */
    public static final int MAX_SECTION_RENDER_BYTES = 8192;

    /** 构造出的请求地址上限（防御性，非协议要求） */
    public static final int MAX_URL_LENGTH = 8192;

    private static final int MAX_PARTS = 64;

    private static final String SECRET_PREFIX = "secret:";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private HttpTemplateEngine() {}

    /** 渲染上下文允许的固定变量 */
    public enum Variable {
        UPLOAD_ID("uploadId"),
        PUBLIC_ID("publicId"),
        FILE_NAME("fileName"),
        EXTENSION("extension"),
        MIME_TYPE("mimeType"),
        SIZE_BYTES("sizeBytes");

        private final String token;

        Variable(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        /** 按变量名查找，未登记返回 null */
        public static Variable of(String token) {
            for (Variable value : values()) {
                if (value.token.equals(token)) {
                    return value;
                }
            }
            return null;
        }
    }

    /** 编译后的模板片段 */
    public sealed interface Part permits Literal, SecretRef, VariableRef {}

    /** 字面量片段 */
    public record Literal(String text) implements Part {}

    /** 命名凭据引用（{@code ${secret:name}}） */
    public record SecretRef(String name) implements Part {}

    /** 固定变量引用（{@code ${publicId}} 等） */
    public record VariableRef(Variable variable) implements Part {}

    /** 编译结果：保留原文以支持序列化与诊断 */
    public record CompiledTemplate(String source, List<Part> parts) {

        /** 模板引用到的命名凭据（按出现顺序去重） */
        public Set<String> secretNames() {
            Set<String> names = new LinkedHashSet<>();
            for (Part part : parts) {
                if (part instanceof SecretRef ref) {
                    names.add(ref.name());
                }
            }
            return names;
        }

        /** 是否引用了至少一个命名凭据 */
        public boolean referencesSecret() {
            for (Part part : parts) {
                if (part instanceof SecretRef) {
                    return true;
                }
            }
            return false;
        }

        /** 是否引用了指定固定变量 */
        public boolean containsVariable(Variable variable) {
            for (Part part : parts) {
                if (part instanceof VariableRef ref && ref.variable() == variable) {
                    return true;
                }
            }
            return false;
        }

        /** 全部字面量片段（用于文件名等安全字符校验） */
        public List<String> literals() {
            List<String> values = new ArrayList<>();
            for (Part part : parts) {
                if (part instanceof Literal literal) {
                    values.add(literal.text());
                }
            }
            return values;
        }
    }

    /** 渲染上下文；{@code secrets} 为当前渠道已配置的命名凭据 */
    public record RenderContext(
            String uploadId,
            String publicId,
            String fileName,
            String extension,
            String mimeType,
            long sizeBytes,
            Map<String, String> secrets) {

        public RenderContext {
            secrets = secrets == null ? Map.of() : Map.copyOf(secrets);
        }
    }

    /**
     * 编译模板：拒绝未知变量、嵌套 {@code ${}}、未闭合变量与超长原文。
     *
     * @param source 模板原文
     * @param field  字段名（用于错误文案定位，不含敏感值）
     * @return 编译结果
     */
    public static CompiledTemplate compile(String source, String field) {
        if (source == null) {
            throw invalid(field, "模板不能为空");
        }
        if (utf8Length(source) > MAX_TEMPLATE_BYTES) {
            throw invalid(field, "模板超过 " + MAX_TEMPLATE_BYTES + " 字节");
        }
        List<Part> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int index = 0;
        while (index < source.length()) {
            int start = source.indexOf("${", index);
            if (start < 0) {
                literal.append(source, index, source.length());
                break;
            }
            literal.append(source, index, start);
            int end = source.indexOf('}', start + 2);
            if (end < 0) {
                throw invalid(field, "模板变量未闭合");
            }
            String body = source.substring(start + 2, end);
            if (body.isEmpty()) {
                throw invalid(field, "模板变量为空");
            }
            if (body.contains("${")) {
                throw invalid(field, "不支持嵌套模板变量");
            }
            flushLiteral(parts, literal);
            parts.add(parseVariable(body, field));
            if (parts.size() > MAX_PARTS) {
                throw invalid(field, "模板片段过多");
            }
            index = end + 1;
        }
        flushLiteral(parts, literal);
        return new CompiledTemplate(source, List.copyOf(parts));
    }

    /**
     * 渲染模板：缺失的固定变量按空串处理，缺失的命名凭据直接拒绝（STORAGE036）。
     *
     * @param template 编译结果
     * @param context  渲染上下文
     * @param field    字段名（用于错误文案定位）
     * @return 渲染结果
     */
    public static String render(CompiledTemplate template, RenderContext context, String field) {
        if (template == null) {
            throw invalid(field, "模板未编译");
        }
        RenderContext safeContext =
                context == null ? new RenderContext(null, null, null, null, null, 0L, Map.of()) : context;
        StringBuilder out = new StringBuilder();
        for (Part part : template.parts()) {
            if (part instanceof Literal literal) {
                out.append(literal.text());
            } else if (part instanceof SecretRef ref) {
                String value = safeContext.secrets().get(ref.name());
                if (value == null || value.isEmpty()) {
                    throw new BusinessException("STORAGE036", ref.name());
                }
                out.append(value);
            } else if (part instanceof VariableRef ref) {
                out.append(variableValue(ref.variable(), safeContext));
            }
        }
        String rendered = out.toString();
        if (utf8Length(rendered) > MAX_RENDERED_VALUE_BYTES) {
            throw invalid(field, "渲染结果超过 " + MAX_RENDERED_VALUE_BYTES + " 字节");
        }
        return rendered;
    }

    /**
     * 批量渲染同一容器（Query / Header / Form）的值，并复核合计上限 8 KiB。
     *
     * @param templates 已编译模板（键为参数名）
     * @param context   渲染上下文
     * @param section   容器名（query / headers / fields）
     * @return 按参数名自然排序的渲染结果
     */
    public static Map<String, String> renderValues(
            Map<String, CompiledTemplate> templates, RenderContext context, String section) {
        Map<String, String> rendered = new TreeMap<>();
        if (templates == null || templates.isEmpty()) {
            return rendered;
        }
        long total = 0;
        for (Map.Entry<String, CompiledTemplate> entry : new TreeMap<>(templates).entrySet()) {
            String value = render(entry.getValue(), context, section + "." + entry.getKey());
            total += utf8Length(value);
            if (total > MAX_SECTION_RENDER_BYTES) {
                throw invalid(section, "渲染结果合计超过 " + MAX_SECTION_RENDER_BYTES + " 字节");
            }
            rendered.put(entry.getKey(), value);
        }
        return rendered;
    }

    /** Header 值渲染后校验：拒绝 CR / LF / NUL 与其他控制字符，不做转义替代 */
    public static void checkHeaderValue(String headerName, String value) {
        if (value == null) {
            throw invalid("headers." + headerName, "Header 值不能为空");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw invalid("headers." + headerName, "Header 值包含控制字符");
            }
        }
    }

    /** Query 键值各编码一次（RFC 3986 非保留字符集以外百分号编码，不整体二次编码） */
    public static String encodeQueryComponent(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~') {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 0x0F]);
            }
        }
        return out.toString();
    }

    /**
     * 构造请求地址：endpoint + 独立 Query + QUERY 认证参数，键值各编码一次。
     *
     * @param profile 已校验画像
     * @param context 渲染上下文
     * @return 完整请求地址
     */
    public static String buildUrl(HttpUploadProfile profile, RenderContext context) {
        if (profile == null || profile.endpoint() == null || profile.endpoint().isBlank()) {
            throw invalid("endpoint", "未配置请求地址");
        }
        Map<String, String> pairs = new TreeMap<>();
        if (profile.query() != null) {
            for (Map.Entry<String, String> entry : profile.query().entrySet()) {
                String field = "query." + entry.getKey();
                pairs.put(entry.getKey(), render(compile(entry.getValue(), field), context, field));
            }
            if (pairs.size() > HttpProfileValidator.MAX_MAP_ENTRIES) {
                throw invalid("query", "Query 参数超过 " + HttpProfileValidator.MAX_MAP_ENTRIES + " 项");
            }
        }
        HttpUploadProfile.Auth auth = profile.auth();
        if (auth != null && HttpUploadProfile.AUTH_QUERY.equals(auth.mode())) {
            pairs.put(auth.name(), render(compile(auth.valueTemplate(), "auth"), context, "auth"));
        }
        StringBuilder url = new StringBuilder(profile.endpoint());
        boolean first = true;
        for (Map.Entry<String, String> entry : pairs.entrySet()) {
            url.append(first ? '?' : '&')
                    .append(encodeQueryComponent(entry.getKey()))
                    .append('=')
                    .append(encodeQueryComponent(entry.getValue()));
            first = false;
        }
        if (url.length() > MAX_URL_LENGTH) {
            throw invalid("endpoint", "请求地址超过 " + MAX_URL_LENGTH + " 字符");
        }
        return url.toString();
    }

    /**
     * 渲染全部请求头（含 HEADER 认证与 BASIC 认证生成的 Authorization），键按大小写不敏感去重。
     *
     * @param profile 已校验画像
     * @param context 渲染上下文
     * @return 请求头（大小写不敏感的有序 Map）
     */
    public static Map<String, String> renderHeaders(HttpUploadProfile profile, RenderContext context) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        long total = 0;
        if (profile.headers() != null) {
            for (Map.Entry<String, String> entry : profile.headers().entrySet()) {
                String field = "headers." + entry.getKey();
                String value = render(compile(entry.getValue(), field), context, field);
                checkHeaderValue(entry.getKey(), value);
                headers.put(entry.getKey(), value);
                total += utf8Length(value);
            }
        }
        HttpUploadProfile.Auth auth = profile.auth();
        if (auth != null && HttpUploadProfile.AUTH_HEADER.equals(auth.mode())) {
            checkHeaderName(auth.name());
            String value = render(compile(auth.valueTemplate(), "auth"), context, "auth");
            checkHeaderValue(auth.name(), value);
            headers.put(auth.name(), value);
            total += utf8Length(value);
        } else if (auth != null && HttpUploadProfile.AUTH_BASIC.equals(auth.mode())) {
            headers.put("Authorization", basicAuthorization(auth, context));
        }
        if (total > MAX_SECTION_RENDER_BYTES) {
            throw invalid("headers", "渲染结果合计超过 " + MAX_SECTION_RENDER_BYTES + " 字节");
        }
        return headers;
    }

    /**
     * 渲染普通 multipart 表单字段（含 FORM 认证字段），不含文件字段本身。
     *
     * @param profile 已校验画像
     * @param context 渲染上下文
     * @return 字段名到文本值（自然排序）
     */
    public static Map<String, String> renderFormFields(HttpUploadProfile profile, RenderContext context) {
        Map<String, String> fields = new TreeMap<>();
        long total = 0;
        if (profile.body() != null && profile.body().fields() != null) {
            for (Map.Entry<String, String> entry : profile.body().fields().entrySet()) {
                String field = "fields." + entry.getKey();
                String value = render(compile(entry.getValue(), field), context, field);
                fields.put(entry.getKey(), value);
                total += utf8Length(value);
            }
        }
        HttpUploadProfile.Auth auth = profile.auth();
        if (auth != null && HttpUploadProfile.AUTH_FORM.equals(auth.mode())) {
            String value = render(compile(auth.valueTemplate(), "auth"), context, "auth");
            fields.put(auth.name(), value);
            total += utf8Length(value);
        }
        if (total > MAX_SECTION_RENDER_BYTES) {
            throw invalid("fields", "渲染结果合计超过 " + MAX_SECTION_RENDER_BYTES + " 字节");
        }
        return fields;
    }

    /**
     * 收集画像引用到的全部命名凭据（Query / Header / Form / 认证 / 文件名模板）。
     *
     * @param profile 画像（可以未校验，模板非法时抛 STORAGE034）
     * @return 按出现顺序去重的凭据名
     */
    public static Set<String> referencedSecretNames(HttpUploadProfile profile) {
        Set<String> names = new LinkedHashSet<>();
        if (profile == null) {
            return names;
        }
        if (profile.query() != null) {
            for (Map.Entry<String, String> entry : profile.query().entrySet()) {
                names.addAll(
                        compile(entry.getValue(), "query." + entry.getKey()).secretNames());
            }
        }
        if (profile.headers() != null) {
            for (Map.Entry<String, String> entry : profile.headers().entrySet()) {
                names.addAll(
                        compile(entry.getValue(), "headers." + entry.getKey()).secretNames());
            }
        }
        HttpUploadProfile.Auth auth = profile.auth();
        if (auth != null) {
            if (auth.valueTemplate() != null) {
                names.addAll(compile(auth.valueTemplate(), "auth").secretNames());
            }
            if (auth.usernameSecret() != null && !auth.usernameSecret().isBlank()) {
                names.add(auth.usernameSecret());
            }
            if (auth.passwordSecret() != null && !auth.passwordSecret().isBlank()) {
                names.add(auth.passwordSecret());
            }
        }
        if (profile.body() != null) {
            if (profile.body().fileNameTemplate() != null) {
                names.addAll(compile(profile.body().fileNameTemplate(), "body.fileNameTemplate")
                        .secretNames());
            }
            if (profile.body().fields() != null) {
                for (Map.Entry<String, String> entry : profile.body().fields().entrySet()) {
                    names.addAll(compile(entry.getValue(), "fields." + entry.getKey())
                            .secretNames());
                }
            }
        }
        return names;
    }

    /** Header 名合法性：可见 ASCII 的 token 字符，拒绝分隔与控制字符 */
    public static void checkHeaderName(String name) {
        if (name == null || name.isEmpty() || name.length() > HttpProfileValidator.MAX_KEY_LENGTH) {
            throw invalid("headers", "Header 名长度非法");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!allowed) {
                throw invalid("headers." + name, "Header 名包含非法字符");
            }
        }
    }

    /** UTF-8 字节数 */
    public static int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private static Part parseVariable(String body, String field) {
        if (body.startsWith(SECRET_PREFIX)) {
            String name = body.substring(SECRET_PREFIX.length());
            if (!HttpSecretEnvelope.isValidSecretName(name)) {
                throw invalid(field, "非法的凭据引用名");
            }
            return new SecretRef(name);
        }
        Variable variable = Variable.of(body);
        if (variable == null) {
            throw invalid(field, "不支持的模板变量: " + safeToken(body));
        }
        return new VariableRef(variable);
    }

    private static void flushLiteral(List<Part> parts, StringBuilder literal) {
        if (literal.length() > 0) {
            parts.add(new Literal(literal.toString()));
            literal.setLength(0);
        }
    }

    private static String variableValue(Variable variable, RenderContext context) {
        return switch (variable) {
            case UPLOAD_ID -> nullToEmpty(context.uploadId());
            case PUBLIC_ID -> nullToEmpty(context.publicId());
            case FILE_NAME -> nullToEmpty(context.fileName());
            case EXTENSION -> nullToEmpty(context.extension());
            case MIME_TYPE -> nullToEmpty(context.mimeType());
            case SIZE_BYTES -> Long.toString(context.sizeBytes());
        };
    }

    private static String basicAuthorization(HttpUploadProfile.Auth auth, RenderContext context) {
        Map<String, String> secrets = context == null ? Map.of() : context.secrets();
        String username = secrets.get(auth.usernameSecret());
        String password = secrets.get(auth.passwordSecret());
        if (username == null || username.isEmpty()) {
            throw new BusinessException("STORAGE036", auth.usernameSecret());
        }
        if (password == null) {
            throw new BusinessException("STORAGE036", auth.passwordSecret());
        }
        if (username.indexOf(':') >= 0) {
            throw new BusinessException("STORAGE037", "Basic 用户名不能包含冒号");
        }
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safeToken(String token) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < token.length() && out.length() < 32; i++) {
            char c = token.charAt(i);
            if ((c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == ':'
                    || c == '_'
                    || c == '-'
                    || c == '.') {
                out.append(c);
            } else {
                out.append('?');
            }
        }
        return out.toString();
    }

    private static BusinessException invalid(String field, String reason) {
        return new BusinessException("STORAGE034", field + " " + reason);
    }
}
