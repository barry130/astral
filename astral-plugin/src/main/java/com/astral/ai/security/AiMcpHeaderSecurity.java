package com.astral.ai.security;

import com.astral.ai.common.AiException;
import com.astral.ai.common.AiJson;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * MCP 附加请求头的敏感值三态处理（R9）。
 *
 * <p>审计契约 docs/audit/2026-11-07/backend-ai-read-recovery.md R9：MCP headers 里的
 * {@code Authorization}/api-key/token/secret 是明文落库的凭据，后台列表/导出/DB 备份即泄露。
 * 与提供方 API Key 同一套「宁可不可用，也不泄露」取舍：</p>
 * <ul>
 *   <li><b>落库</b>（{@link #protectAtRest}）：敏感键的明文值经 {@link AiSecretCipher} AES-256-GCM
 *       加密为 {@code v1:...} 密文；非敏感键原样保留（运维可读）；{@code ${secret:key}} 引用与既有
 *       密文不再二次加密；回显掩码 {@value #MASK} 表示「该敏感头未改动」，从旧行密文原样沿用
 *       （旧行遗留明文在保存时顺势重加密，遗留行自愈）。</li>
 *   <li><b>调用</b>（{@link #revealForCall}）：{@code v1:} 密文解出明文；解不开（KEK 变更/密文损坏）
 *       按「凭证缺失」整条省略——宁可远端 401，也不把密文当明文发出去；遗留明文与
 *       {@code ${secret:key}} 引用原样放行（后者由 {@code McpClient.renderHeaders} 解析）。</li>
 *   <li><b>回显</b>（{@link #maskForDisplay}）：敏感键值一律 {@value #MASK}（不看存储形态），
 *       非敏感值原样；结构无法解析时回 {@code null}，绝不回原文。</li>
 * </ul>
 *
 * <p>KEK 未配置时敏感明文拒绝落库（AI006，与提供方同款）；headers JSON 无法解析时拒绝落库
 * （AI008）——存不进去的配置比存错好。落库/调用两端取 KEK 的方式与
 * {@code McpToolExecutor} 一致：从 {@code AiProperties.getSecretKey()} 现场读取，不持有状态。</p>
 */
public final class AiMcpHeaderSecurity {

    /** 回显脱敏占位值；保存时视为「未改动该敏感头」 */
    public static final String MASK = "******";

    private static final Pattern SECRET_REFERENCE = Pattern.compile("\\$\\{secret:[A-Za-z0-9_\\-.]+}");

    private AiMcpHeaderSecurity() {}

    /** 敏感头名称判定（小写包含匹配）：authorization / api-key / apikey / token / secret。 */
    static boolean isSensitiveName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("authorization")
                || lower.contains("api-key")
                || lower.contains("apikey")
                || lower.contains("token")
                || lower.contains("secret");
    }

    /**
     * 落库前保护。
     *
     * @param secretKey     KEK（astral.plugins.ai.secret-key；空表示未配置）
     * @param headersJson   本次提交的 headers JSON（null/blank 表示清空，直接回 null）
     * @param previousJson  旧行已存的 headers JSON（新建传 null；掩码合并的取值来源）
     * @return 可安全落库的 headers JSON
     */
    public static String protectAtRest(String secretKey, String headersJson, String previousJson) {
        ObjectNode source = requireObject(headersJson);
        if (source == null) {
            return null;
        }
        ObjectNode previous = asObject(previousJson);
        ObjectNode result = AiJson.mapper().createObjectNode();
        Set<String> names = new HashSet<>();
        source.properties().forEach(entry -> {
            String name = entry.getKey();
            if (!names.add(name.toLowerCase(Locale.ROOT))) {
                throw new AiException("AI008", "请求头名称不能重复（不区分大小写）");
            }
            JsonNode node = entry.getValue();
            if (node == null || node.isNull()) {
                return; // 与 McpClient.renderHeaders 一致：null 值跳过
            }
            if (!node.isString()) {
                throw new AiException("AI008", "请求头值必须是字符串或 null");
            }
            String value = node.asString();
            if (!isSensitiveName(name)) {
                result.put(name, value);
                return;
            }
            if (value.startsWith("v1:")) {
                if (AiSecretCipher.decryptWithKey(secretKey, value) == null) {
                    throw new AiException("AI008", "请求头密文无法验证，请重新填写凭据或保留回显掩码");
                }
                result.put(name, value);
                return;
            }
            if (SECRET_REFERENCE.matcher(value).matches()) {
                result.put(name, value);
                return;
            }
            if (MASK.equals(value)) {
                JsonNode old = previous == null
                        ? null
                        : previous.properties().stream()
                                .filter(header -> header.getKey().equalsIgnoreCase(name))
                                .map(java.util.Map.Entry::getValue)
                                .findFirst()
                                .orElse(null);
                if (old == null || !old.isString()) {
                    return; // 孤儿掩码：旧行没有该敏感头，本次也没有真实值可存，省略
                }
                String oldValue = old.asString();
                result.put(
                        name,
                        oldValue.startsWith("v1:")
                                        || SECRET_REFERENCE.matcher(oldValue).matches()
                                ? oldValue // 未改动：沿用旧行
                                : encrypt(secretKey, oldValue)); // 旧行遗留明文：顺势重加密
                return;
            }
            result.put(name, encrypt(secretKey, value));
        });
        return AiJson.write(result);
    }

    /**
     * 调用前还原：解密 {@code v1:} 密文，解不开的敏感头整条省略。
     *
     * @param secretKey    KEK（未配置时所有密文按缺失处理）
     * @param headersJson  数据库中的 headers JSON
     * @return 可直接交给 {@code McpClient} 的 headers JSON（解不开的敏感头已省略）
     */
    public static String revealForCall(String secretKey, String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return null;
        }
        ObjectNode source = asObject(headersJson);
        if (source == null) {
            return null; // 解析失败按「无 headers」处理，不外发原文
        }
        ObjectNode result = AiJson.mapper().createObjectNode();
        source.properties().forEach(entry -> {
            String name = entry.getKey();
            JsonNode node = entry.getValue();
            if (node == null || !node.isString()) {
                return;
            }
            String value = node.asString();
            if (!isSensitiveName(name) || !value.startsWith("v1:")) {
                result.put(name, value); // 非敏感 / secret 引用 / 遗留明文：原样
                return;
            }
            String plain = AiSecretCipher.decryptWithKey(secretKey, value);
            if (plain != null) {
                result.put(name, plain);
            } // 解不开：整条省略（宁可 401，不发密文）
        });
        return result.isEmpty() ? null : AiJson.write(result);
    }

    /**
     * 回显脱敏：敏感键值一律 {@value #MASK}，非敏感值原样；解析不了回 null。
     *
     * @param headersJson 数据库中的 headers JSON
     * @return 可下发给前端的 headers JSON
     */
    public static String maskForDisplay(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return null;
        }
        ObjectNode source = asObject(headersJson);
        if (source == null) {
            return null;
        }
        ObjectNode result = AiJson.mapper().createObjectNode();
        source.properties().forEach(entry -> {
            JsonNode node = entry.getValue();
            if (node == null || !node.isString()) {
                return;
            }
            result.put(entry.getKey(), isSensitiveName(entry.getKey()) ? MASK : node.asString());
        });
        return AiJson.write(result);
    }

    /** 落库前的结构校验：必须是可解析的 JSON 对象，否则拒绝保存（AI008）。 */
    private static ObjectNode requireObject(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return null;
        }
        ObjectNode source = asObject(headersJson);
        if (source == null) {
            throw new AiException("AI008", "请求头 JSON 非法，必须是对象结构");
        }
        return source;
    }

    /** 宽容解析：对象结构返回 ObjectNode（空对象也算），否则 null。 */
    private static ObjectNode asObject(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return null;
        }
        JsonNode parsed = AiJson.read(headersJson);
        return parsed instanceof ObjectNode objectNode ? objectNode : null;
    }

    /** KEK 未配置时拒绝落库（AI006，与提供方 API Key 同款取舍）。 */
    private static String encrypt(String secretKey, String plain) {
        if (secretKey == null || secretKey.isBlank()) {
            throw new AiException(
                    "AI006",
                    "未配置 astral.plugins.ai.secret-key，拒绝保存明文敏感请求头；"
                            + "请用环境变量 ASTRAL_AI_SECRET_KEY 注入，或改用 ${secret:KEY} 引用环境变量");
        }
        return AiSecretCipher.encryptWithKey(secretKey, plain);
    }
}
