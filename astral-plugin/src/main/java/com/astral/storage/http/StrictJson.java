package com.astral.storage.http;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 渠道画像与上游响应用的严格 JSON 解析。
 *
 * <p>设计约束（v1.1 §3.1 / §3.4）：未知字段、重复 JSON 键、类型不符、超深/超长文本一律拒绝，
 * 不静默忽略拼错的安全设置。这里的 {@link ObjectMapper} 是**专用**实例，不复用 Spring 全局
 * mapper（全局 mapper 关闭了未知字段报错）。</p>
 *
 * <p>解析结果统一为纯 Java 树（{@code Map}/{@code List}/{@code String}/{@code Number}/{@code Boolean}/null），
 * 不用 {@code JsonNode}，避免在对树做递归校验时依赖节点 API 细节。</p>
 */
public final class StrictJson {

    /** 解析失败（调用方负责翻译成业务错误码与安全文案，异常消息里不含原始正文） */
    public static class StrictJsonException extends RuntimeException {
        public StrictJsonException(String message) {
            super(message);
        }

        public StrictJsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 上游响应正文上限 256 KiB */
    public static final int MAX_RESPONSE_BYTES = 262144;
    /** 树最大嵌套深度 */
    public static final int MAX_DEPTH = 32;
    /** 单个字符串上限 16 KiB */
    public static final int MAX_STRING_LENGTH = 16384;
    /** 单个数值的文本长度上限 */
    public static final int MAX_NUMBER_TEXT_LENGTH = 32;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private StrictJson() {}

    /**
     * 反序列化到具体类型（用于渠道画像）。
     *
     * @param json     原始 JSON
     * @param type     目标类型
     * @param maxBytes 输入字节上限
     * @return 反序列化结果
     */
    public static <T> T read(String json, Class<T> type, int maxBytes) {
        requireSize(json, maxBytes);
        try {
            T value = MAPPER.readValue(json, type);
            if (value == null) {
                throw new StrictJsonException("payload must be a JSON object");
            }
            return value;
        } catch (StrictJsonException e) {
            throw e;
        } catch (RuntimeException e) {
            // 不回显原始正文，只带异常类型，避免把上游内容写进日志/响应
            throw new StrictJsonException("json binding failed: " + e.getClass().getSimpleName(), e);
        }
    }

    /**
     * 解析为纯 Java 树并做深度/长度校验。
     *
     * @param json     原始 JSON
     * @param maxBytes 输入字节上限
     * @return Map/List/String/Number/Boolean/null
     */
    public static Object readTree(String json, int maxBytes) {
        requireSize(json, maxBytes);
        Object root;
        try {
            root = MAPPER.readValue(json, Object.class);
        } catch (RuntimeException e) {
            throw new StrictJsonException("json parse failed: " + e.getClass().getSimpleName(), e);
        }
        checkTree(root);
        return root;
    }

    /** 校验树深度、字符串长度与数值文本长度（迭代实现，不递归）。 */
    public static void checkTree(Object root) {
        Deque<Object[]> stack = new ArrayDeque<>();
        stack.push(new Object[] {root, 1});
        while (!stack.isEmpty()) {
            Object[] frame = stack.pop();
            Object node = frame[0];
            int depth = (Integer) frame[1];
            if (depth > MAX_DEPTH) {
                throw new StrictJsonException("json nesting too deep");
            }
            if (node instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key) || key.length() > MAX_STRING_LENGTH) {
                        throw new StrictJsonException("json object key too long");
                    }
                    stack.push(new Object[] {entry.getValue(), depth + 1});
                }
            } else if (node instanceof List<?> list) {
                for (Object item : list) {
                    stack.push(new Object[] {item, depth + 1});
                }
            } else if (node instanceof String text) {
                if (text.length() > MAX_STRING_LENGTH) {
                    throw new StrictJsonException("json string too long");
                }
            } else if (node instanceof BigDecimal number) {
                if (number.toPlainString().length() > MAX_NUMBER_TEXT_LENGTH) {
                    throw new StrictJsonException("json number too long");
                }
            } else if (node instanceof Number number) {
                if (number.toString().length() > MAX_NUMBER_TEXT_LENGTH) {
                    throw new StrictJsonException("json number too long");
                }
            } else if (node != null && !(node instanceof Boolean)) {
                throw new StrictJsonException("json unsupported node type");
            }
        }
    }

    /**
     * 按 RFC 6901 解析 JSON Pointer（只支持对象下标与数组下标，不支持通配/相对路径）。
     *
     * @param root    树根
     * @param pointer 形如 {@code /data/url} 的指针，空串表示根
     * @return 命中值；不存在时返回 {@link #MISSING}
     */
    public static Object pointer(Object root, String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return root;
        }
        if (!pointer.startsWith("/")) {
            throw new StrictJsonException("pointer must start with '/'");
        }
        Object current = root;
        for (String rawToken : pointer.substring(1).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    return MISSING;
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int index = arrayIndex(token);
                if (index < 0 || index >= list.size()) {
                    return MISSING;
                }
                current = list.get(index);
            } else {
                return MISSING;
            }
        }
        return current;
    }

    /** 指针未命中的哨兵值 */
    public static final Object MISSING = new Object() {
        @Override
        public String toString() {
            return "<missing>";
        }
    };

    private static int arrayIndex(String token) {
        if (token.isEmpty() || token.length() > 9) {
            return -1;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(token);
    }

    /** 把指针命中值转成文本（只接受字符串或数字，其他类型返回 null）。 */
    public static String textAt(Object root, String pointer) {
        Object value = pointer(root, pointer);
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return null;
    }

    /** 收集树里所有字符串（用于凭据反射检测）。 */
    public static List<String> stringsOf(Object root) {
        List<String> values = new ArrayList<>();
        Deque<Object> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Object node = stack.pop();
            if (node instanceof String text) {
                values.add(text);
            } else if (node instanceof Map<?, ?> map) {
                map.values().forEach(stack::push);
            } else if (node instanceof List<?> list) {
                list.forEach(stack::push);
            }
        }
        return values;
    }

    private static void requireSize(String json, int maxBytes) {
        if (json == null || json.isBlank()) {
            throw new StrictJsonException("payload is empty");
        }
        if (json.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new StrictJsonException("payload too large");
        }
    }
}
