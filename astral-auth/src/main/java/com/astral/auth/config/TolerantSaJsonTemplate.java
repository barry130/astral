package com.astral.auth.config;

import cn.dev33.satoken.exception.SaJsonConvertException;
import cn.dev33.satoken.json.SaJsonTemplate;
import cn.dev33.satoken.json.SaJsonTemplateForJackson3;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ext.javatime.deser.LocalDateTimeDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;
import tools.jackson.databind.module.SimpleModule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 兼容新旧两种日期格式的 Sa-Token JSON 模板（Jackson 3）。
 *
 * <p><b>背景</b>：Sa-Token 在不同大版本里对 {@code java.time} 的处理不一致——</p>
 * <ul>
 *   <li>1.42（Jackson 2，{@code SaJsonTemplateForJackson}）给 ObjectMapper 注册了
 *       {@code JavaTimeModule}，把 {@code LocalDateTime} 固定写成 {@code yyyy-MM-dd HH:mm:ss}；</li>
 *   <li>1.46（Jackson 3，{@code SaJsonTemplateForJackson3}）不再定制 java.time，
 *       于是按 Jackson 3 默认的 ISO-8601（{@code yyyy-MM-dd'T'HH:mm:ss}）读写。</li>
 * </ul>
 *
 * <p>会话是持久化在 Redis 里的，且本项目 {@code REDIS_DB=7} 被本地开发与线上部署共用，
 * 于是旧版本写入的会话（{@code ["java.time.LocalDateTime","2026-09-27 17:42:07"]}）
 * 在新版本读取时抛 {@code SaJsonConvertException: Cannot deserialize value of type
 * java.time.LocalDateTime from String "2026-09-27 17:42:07"}，登录接口直接 500。</p>
 *
 * <p><b>本类做法</b>：完全复刻 {@link SaJsonTemplateForJackson3} 的 Mapper 配置
 * （沿用同一个类型白名单校验器，安全策略不变），只额外挂一个能同时解析
 * ISO-8601 与 {@code yyyy-MM-dd HH:mm:ss} 的 {@code LocalDateTime} 反序列化器。
 * 写出去仍然是 Jackson 3 默认的 ISO-8601，读进来新旧格式都认。</p>
 *
 * <p>安装方式见 {@link SaTokenJsonConfig}。</p>
 */
public class TolerantSaJsonTemplate implements SaJsonTemplate {

    /**
     * 依次尝试的日期时间格式。
     * <p>顺序有讲究：ISO 放最前，保证新写入的数据走最快路径。</p>
     */
    private static final DateTimeFormatter[] LOCAL_DATE_TIME_FORMATS = {
            // 2026-09-27T17:42:07（Jackson 3 / Sa-Token 1.46 默认）
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            // 2026-09-27 17:42:07（Sa-Token 1.42 的 DATE_TIME_PATTERN）
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            // 2026-09-27 17:42:07.123
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
    };

    /** 带类型信息（{@code @class}）的 Mapper，用于 SaSession 等对象 */
    private final JsonMapper objectMapper;

    /** 不带类型信息的 Mapper，用于 Map 结构 */
    private final JsonMapper mapObjectMapper;

    public TolerantSaJsonTemplate() {
        // 复用 Sa-Token 自己的类型白名单校验器，避免放宽反序列化安全边界
        PolymorphicTypeValidator typeValidator = SaJsonTemplateForJackson3.buildAllowTypeValidator();

        this.objectMapper = JsonMapper.builder()
                .activateDefaultTypingAsProperty(typeValidator, DefaultTyping.NON_FINAL, "@class")
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(localDateTimeModule())
                .build();

        this.mapObjectMapper = JsonMapper.builder()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addModule(localDateTimeModule())
                .build();
    }

    private static SimpleModule localDateTimeModule() {
        SimpleModule module = new SimpleModule("astral-tolerant-java-time");
        module.addDeserializer(LocalDateTime.class, new TolerantLocalDateTimeDeserializer());
        return module;
    }

    @Override
    public String objectToJson(Object object) {
        if (object == null) {
            return null;
        }
        try {
            // 与 SaJsonTemplateForJackson3 保持一致：Map 走不带类型信息的 Mapper
            return object instanceof Map
                    ? mapObjectMapper.writeValueAsString(object)
                    : objectMapper.writeValueAsString(object);
        } catch (JacksonException e) {
            throw new SaJsonConvertException(e);
        }
    }

    @Override
    public <T> T jsonToObject(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JacksonException e) {
            throw new SaJsonConvertException(e);
        }
    }

    @Override
    public Object jsonToObject(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new SaJsonConvertException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> jsonToMap(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return mapObjectMapper.readValue(json, Map.class);
        } catch (JacksonException e) {
            throw new SaJsonConvertException(e);
        }
    }

    /**
     * 宽容的 {@code LocalDateTime} 反序列化器：字符串按多套格式依次尝试，
     * 数字（时间戳）与数组（Jackson 2 默认的时间戳形态）交回 Jackson 原生实现。
     */
    static final class TolerantLocalDateTimeDeserializer extends ValueDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
            if (p.currentToken() == JsonToken.VALUE_STRING) {
                String text = p.getString();
                if (text == null || text.isBlank()) {
                    return null;
                }
                String value = text.trim();
                for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATS) {
                    try {
                        return LocalDateTime.parse(value, formatter);
                    } catch (DateTimeParseException ignored) {
                        // 换下一种格式继续试
                    }
                }
                // 兜底：字符串形式的毫秒时间戳
                try {
                    return LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(Long.parseLong(value)), ZoneId.systemDefault());
                } catch (NumberFormatException ignored) {
                    // 走到这里说明真的解析不了，抛出可读异常
                }
                throw new DateTimeParseException(
                        "无法解析 LocalDateTime，期望 ISO-8601 或 yyyy-MM-dd HH:mm:ss，实际为 '" + value + "'",
                        value, 0);
            }
            // 数字 / 数组等形态沿用 Jackson 原生行为
            return LocalDateTimeDeserializer.INSTANCE.deserialize(p, ctxt);
        }
    }
}
