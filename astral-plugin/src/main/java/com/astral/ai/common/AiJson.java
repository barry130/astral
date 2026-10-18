package com.astral.ai.common;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 模块 JSON 工具。
 *
 * <p>项目统一使用 Jackson 3（{@code tools.jackson.*}）。这里集中提供一个共享
 * {@link ObjectMapper} 与若干「不抛受检异常」的读写帮助方法，避免每个 Service 里
 * 重复 try/catch，也避免各处 new ObjectMapper() 造成的配置漂移。</p>
 */
public final class AiJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AiJson() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 序列化；失败返回 null（调用方按「无内容」处理，不中断主流程） */
    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String text) {
            return text;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 JSON；空串或非法内容返回 null */
    public static JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 JSON，失败返回空对象（便于链式取值） */
    public static ObjectNode readObject(String json) {
        JsonNode node = read(json);
        if (node instanceof ObjectNode objectNode) {
            return objectNode;
        }
        return MAPPER.createObjectNode();
    }

    /** 解析 JSON，失败返回空数组 */
    public static ArrayNode readArray(String json) {
        JsonNode node = read(json);
        if (node instanceof ArrayNode arrayNode) {
            return arrayNode;
        }
        return MAPPER.createArrayNode();
    }

    /** 取字符串字段，缺失返回 def */
    public static String text(JsonNode node, String field, String def) {
        if (node == null) {
            return def;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return def;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? def : text;
    }

    /** 取整数字段，缺失返回 def */
    public static int intValue(JsonNode node, String field, int def) {
        if (node == null) {
            return def;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return def;
        }
        return value.asInt();
    }

    /** 取布尔字段，缺失返回 def */
    public static boolean bool(JsonNode node, String field, boolean def) {
        if (node == null) {
            return def;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isBoolean()) {
            return def;
        }
        return value.asBoolean();
    }

    /** 取字符串数组 */
    public static List<String> stringList(JsonNode node, String field) {
        List<String> result = new ArrayList<>();
        if (node == null) {
            return result;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return result;
        }
        for (JsonNode item : value) {
            if (item != null && !item.isNull()) {
                String text = item.asString();
                if (text != null && !text.isBlank()) {
                    result.add(text.trim());
                }
            }
        }
        return result;
    }

    /** 逗号分隔字符串转列表（用于 client_types 这类轻量多值列） */
    public static List<String> splitCsv(String csv) {
        List<String> result = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String item : csv.split(",")) {
            String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return result;
    }

    /** 列表转逗号分隔字符串 */
    public static String joinCsv(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned.isEmpty() ? null : String.join(",", cleaned);
    }

    /** 截断字符串，避免超长内容撑爆列宽 */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    /** 判断字符串是否是合法 JSON 对象或数组 */
    public static boolean isJsonStructured(String text) {
        JsonNode node = read(text);
        return node != null && (node.isObject() || node.isArray());
    }
}
