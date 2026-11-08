package com.astral.storage.http;

import com.astral.common.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * CUSTOM_HTTP 渠道的内部配置信封（存于 {@code sys_storage_config.provider_options}）。
 *
 * <p>设计约束（v1.1 §4.1 / §4.2）：信封内含 schemaVersion、有效画像、服务端凭据绑定与 credential entries；
 * 独立 View <b>永不</b>序列化 credential entries，只输出 {@code configuredSecrets:[{name,configured,updatedAt}]}。
 * 凭据条目绑定 channelId + origin + 上传路径，改变绑定后必须 CLEAR/SET，不能 KEEP 搬运。</p>
 *
 * <p>解析一律经 {@link StrictJson}（未知字段、重复键、超深/超长一律拒绝），序列化使用注入的
 * {@link ObjectMapper}；{@link Credential} 的 {@code toString()} 不输出凭据值，避免误入日志。</p>
 */
public record HttpSecretEnvelope(
        Integer schemaVersion, HttpUploadProfile profile, Binding binding, List<Credential> credentials) {

    /** 信封结构版本（与画像 schemaVersion 是两个概念） */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    /** 信封序列化上限：画像 16 KiB + 8 × 2 KiB 凭据 + 结构开销 */
    public static final int MAX_ENVELOPE_BYTES = 65536;

    /** 单个凭据值上限（UTF-8 字节） */
    public static final int MAX_SECRET_BYTES = 2048;

    /** 每渠道凭据数量上限 */
    public static final int MAX_SECRETS = 8;

    /** 凭据名上限长度 */
    public static final int MAX_SECRET_NAME_LENGTH = 32;

    public static final String OP_KEEP = "KEEP";
    public static final String OP_SET = "SET";
    public static final String OP_CLEAR = "CLEAR";

    /** 掩码值：不得作为凭据值提交 */
    public static final String MASK = "******";

    private static final Pattern SECRET_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,31}$");

    /** 服务端绑定证明：客户端不得提交或改写 */
    public record Binding(Long channelId, String origin, String path) {}

    /** 服务端凭据条目；{@code updatedAt} 为 ISO-8601 本地时间文本 */
    public record Credential(String name, String value, String updatedAt) {

        @Override
        public String toString() {
            return "Credential[name=" + name + ", configured=" + (value != null && !value.isEmpty()) + ", updatedAt="
                    + updatedAt + "]";
        }
    }

    /** 凭据变更判别联合：KEEP / SET / CLEAR */
    public record SecretChange(String op, String value) {

        @Override
        public String toString() {
            return "SecretChange[op=" + op + "]";
        }
    }

    /** 读出视图：只告知是否已配置，不提供明文 */
    public record SecretView(String name, boolean configured, String updatedAt) {}

    /** 凭据名合法性：{@code [A-Za-z][A-Za-z0-9_]{0,31}} */
    public static boolean isValidSecretName(String name) {
        return name != null && SECRET_NAME.matcher(name).matches();
    }

    /**
     * 严格解析信封。
     *
     * @param json provider_options 原文
     * @return 解析并校验后的信封
     * @throws BusinessException STORAGE034：结构非法
     */
    public static HttpSecretEnvelope parse(String json) {
        if (json == null || json.isBlank()) {
            throw new BusinessException("STORAGE034", "渠道凭据信封缺失");
        }
        HttpSecretEnvelope envelope;
        try {
            envelope = StrictJson.read(json, HttpSecretEnvelope.class, MAX_ENVELOPE_BYTES);
        } catch (StrictJson.StrictJsonException e) {
            throw new BusinessException("STORAGE034", "渠道凭据信封损坏");
        }
        envelope.requireValid();
        return envelope;
    }

    /**
     * 序列化信封（仅服务端写库路径调用）。
     *
     * @param envelope 信封
     * @param mapper   注入的 ObjectMapper
     * @return JSON 文本
     * @throws BusinessException STORAGE034：结构非法或超长
     */
    public static String write(HttpSecretEnvelope envelope, ObjectMapper mapper) {
        if (envelope == null) {
            throw new BusinessException("STORAGE034", "渠道凭据信封缺失");
        }
        if (mapper == null) {
            throw new IllegalStateException("序列化渠道凭据信封需要 ObjectMapper");
        }
        envelope.requireValid();
        String json;
        try {
            json = mapper.writeValueAsString(envelope);
        } catch (RuntimeException e) {
            throw new BusinessException("STORAGE034", "渠道凭据信封序列化失败");
        }
        if (HttpTemplateEngine.utf8Length(json) > MAX_ENVELOPE_BYTES) {
            throw new BusinessException("STORAGE034", "渠道凭据信封超过 " + MAX_ENVELOPE_BYTES + " 字节");
        }
        return json;
    }

    /** 结构校验（解析与写库前都执行） */
    public void requireValid() {
        if (schemaVersion == null || schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new BusinessException("STORAGE034", "凭据信封 schemaVersion 只支持 " + CURRENT_SCHEMA_VERSION);
        }
        if (profile == null) {
            throw new BusinessException("STORAGE034", "凭据信封缺少渠道画像");
        }
        if (binding == null || binding.channelId() == null || binding.origin() == null || binding.path() == null) {
            throw new BusinessException("STORAGE034", "凭据信封缺少绑定信息");
        }
        if (binding.origin().isBlank() || binding.path().isBlank()) {
            throw new BusinessException("STORAGE034", "凭据信封绑定信息不完整");
        }
        for (Credential credential : safeCredentials()) {
            if (credential == null || !isValidSecretName(credential.name())) {
                throw new BusinessException("STORAGE034", "凭据名非法");
            }
            validateSecretValue(credential.name(), credential.value());
            if (credential.updatedAt() == null || credential.updatedAt().length() > 32) {
                throw new BusinessException("STORAGE034", "凭据更新时间缺失");
            }
        }
        if (credentials() != null && credentials().size() > MAX_SECRETS) {
            throw new BusinessException("STORAGE034", "凭据数量超过 " + MAX_SECRETS);
        }
        TreeSet<String> names = new TreeSet<>();
        for (Credential credential : safeCredentials()) {
            if (!names.add(credential.name())) {
                throw new BusinessException("STORAGE034", "凭据名重复");
            }
        }
    }

    /** 已配置凭据名（自然排序） */
    public Collection<String> credentialNames() {
        return new TreeSet<>(secretValues().keySet());
    }

    /** 凭据名 → 明文值（仅服务端内部渲染使用，禁止序列化到任何 View） */
    public Map<String, String> secretValues() {
        Map<String, String> values = new LinkedHashMap<>();
        for (Credential credential : safeCredentials()) {
            values.put(credential.name(), credential.value());
        }
        return values;
    }

    /** 已配置数量 */
    public int configuredCount() {
        return safeCredentials().size();
    }

    /** 替换画像（凭据绑定不变） */
    public HttpSecretEnvelope withProfile(HttpUploadProfile newProfile) {
        return new HttpSecretEnvelope(schemaVersion, newProfile, binding, credentials);
    }

    /**
     * 按三态变更计算新信封。
     *
     * <p>绑定（channelId / origin / path）发生变化时：旧凭据一律不搬运，KEEP 直接拒绝（STORAGE037），
     * 只有显式 SET 能建立新绑定下的凭据。变更后仍存在未被画像引用的凭据同样拒绝。</p>
     *
     * @param newProfile 新画像
     * @param newBinding 新绑定
     * @param changes    凭据名 → 三态变更（可为 null）
     * @return 新信封
     */
    public HttpSecretEnvelope applyChanges(
            HttpUploadProfile newProfile, Binding newBinding, Map<String, SecretChange> changes) {
        if (newBinding == null || newBinding.channelId() == null) {
            throw new BusinessException("STORAGE034", "缺少渠道绑定信息");
        }
        boolean bindingChanged = binding == null
                || !java.util.Objects.equals(binding.channelId(), newBinding.channelId())
                || !java.util.Objects.equals(binding.origin(), newBinding.origin())
                || !java.util.Objects.equals(binding.path(), newBinding.path());
        Map<String, SecretChange> safeChanges = changes == null ? Map.of() : changes;
        Collection<String> referenced = HttpTemplateEngine.referencedSecretNames(newProfile);
        for (String name : safeChanges.keySet()) {
            if (!isValidSecretName(name)) {
                throw new BusinessException("STORAGE037", "凭据名非法");
            }
            if (!referenced.contains(name)) {
                throw new BusinessException("STORAGE037", "凭据未被画像引用");
            }
        }
        Map<String, Credential> next = new TreeMap<>();
        if (!bindingChanged) {
            for (Credential credential : safeCredentials()) {
                next.put(credential.name(), credential);
            }
        }
        for (Map.Entry<String, SecretChange> entry : new TreeMap<>(safeChanges).entrySet()) {
            SecretChange change = entry.getValue();
            String op = change == null ? null : normalizeOp(change.op());
            if (op == null) {
                throw new BusinessException("STORAGE037", "凭据变更缺少合法 op");
            }
            switch (op) {
                case OP_KEEP -> {
                    if (bindingChanged) {
                        throw new BusinessException("STORAGE037", "目标绑定已变化，不能保留旧凭据");
                    }
                    if (!next.containsKey(entry.getKey())) {
                        throw new BusinessException("STORAGE036", entry.getKey());
                    }
                }
                case OP_SET -> {
                    validateSecretValue(entry.getKey(), change.value());
                    next.put(
                            entry.getKey(),
                            new Credential(
                                    entry.getKey(),
                                    change.value(),
                                    LocalDateTime.now().toString()));
                }
                case OP_CLEAR -> next.remove(entry.getKey());
                default -> throw new BusinessException("STORAGE037", "不支持的凭据变更操作");
            }
        }
        if (next.size() > MAX_SECRETS) {
            throw new BusinessException("STORAGE037", "凭据数量超过 " + MAX_SECRETS);
        }
        for (String name : next.keySet()) {
            if (!referenced.contains(name)) {
                throw new BusinessException("STORAGE037", "凭据未被画像引用");
            }
        }
        return new HttpSecretEnvelope(CURRENT_SCHEMA_VERSION, newProfile, newBinding, List.copyOf(next.values()));
    }

    /**
     * 校验凭据完备性。
     *
     * <p>ENABLED 渠道必须配齐画像引用的全部凭据（STORAGE036）；DISABLED 渠道允许缺失，便于后续补齐。
     * 任何状态下都不允许存在未被画像引用的残留凭据（STORAGE037）。</p>
     *
     * @param currentProfile 当前画像
     * @param enabled        是否以启用状态保存
     */
    public void requireSecrets(HttpUploadProfile currentProfile, boolean enabled) {
        Collection<String> referenced = HttpTemplateEngine.referencedSecretNames(currentProfile);
        Map<String, String> configured = secretValues();
        if (enabled) {
            for (String name : referenced) {
                if (!configured.containsKey(name)) {
                    throw new BusinessException("STORAGE036", name);
                }
            }
        }
        for (String name : configured.keySet()) {
            if (!referenced.contains(name)) {
                throw new BusinessException("STORAGE037", "凭据未被画像引用");
            }
        }
    }

    /**
     * 读出视图：只含有画像引用的凭据名，标注是否已配置。
     *
     * @param currentProfile 当前画像
     * @return 按名称排序的安全视图
     */
    public List<SecretView> secretViews(HttpUploadProfile currentProfile) {
        Map<String, Credential> configured = new TreeMap<>();
        for (Credential credential : safeCredentials()) {
            configured.put(credential.name(), credential);
        }
        List<SecretView> views = new ArrayList<>();
        for (String name : new TreeSet<>(HttpTemplateEngine.referencedSecretNames(currentProfile))) {
            Credential credential = configured.get(name);
            views.add(new SecretView(name, credential != null, credential == null ? null : credential.updatedAt()));
        }
        return views;
    }

    /** 校验单个凭据值：非空、非纯空白、非掩码、≤2048 字节、无控制字符 */
    public static void validateSecretValue(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("STORAGE037", "凭据值不能为空");
        }
        if (MASK.equals(value.trim())) {
            throw new BusinessException("STORAGE037", "凭据值不能是掩码");
        }
        if (HttpTemplateEngine.utf8Length(value) > MAX_SECRET_BYTES) {
            throw new BusinessException("STORAGE037", "凭据值超过 " + MAX_SECRET_BYTES + " 字节");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                throw new BusinessException("STORAGE037", "凭据值包含控制字符");
            }
        }
        if (!isValidSecretName(name)) {
            throw new BusinessException("STORAGE037", "凭据名非法");
        }
    }

    private List<Credential> safeCredentials() {
        return credentials == null ? List.of() : credentials;
    }

    private static String normalizeOp(String op) {
        if (op == null) {
            return null;
        }
        String normalized = op.trim().toUpperCase(java.util.Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
