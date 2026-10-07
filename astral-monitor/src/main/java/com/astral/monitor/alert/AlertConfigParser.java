package com.astral.monitor.alert;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * 渠道 config JSON 容错解析（引擎发送与管理端校验共用）
 *
 * <p>config 为 TEXT 列存 JSON：
 * EMAIL → {@code {"to": "收件邮箱"}}；
 * SMS → {@code {"phone": "手机号"}}；
 * WEBHOOK → {@code {"url": "...", "secret": "可选", "header": "可选，默认 X-Astral-Alert"}}。
 * 解析失败返回空 Map，由调用方按「缺必填项」报错，避免 JSON 异常直接 500。</p>
 */
public class AlertConfigParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, String> parse(String configJson) {
        Map<String, String> result = new HashMap<>();
        if (configJson == null || configJson.isBlank()) {
            return result;
        }
        try {
            Map<?, ?> raw = mapper.readValue(configJson, Map.class);
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
        } catch (Exception ignored) {
            // 容错：交给调用方按缺必填项处理
        }
        return result;
    }

    /** 校验渠道配置完整性，返回 null=通过，否则为错误文案 */
    public String validate(String type, String configJson) {
        Map<String, String> config = parse(configJson);
        String normalizedType = type == null ? "" : type.trim().toUpperCase();
        return switch (normalizedType) {
            case AlertEngine.CHANNEL_EMAIL -> config.get("to") == null || config.get("to").isBlank()
                    ? "EMAIL 渠道 config 缺少 to（收件邮箱）" : null;
            case AlertEngine.CHANNEL_WEBHOOK -> config.get("url") == null || config.get("url").isBlank()
                    ? "WEBHOOK 渠道 config 缺少 url" : null;
            case AlertEngine.CHANNEL_SMS -> config.get("phone") == null || config.get("phone").isBlank()
                    ? "SMS 渠道 config 缺少 phone（手机号）" : null;
            default -> "不支持的渠道类型：" + type + "（仅 EMAIL / SMS / WEBHOOK）";
        };
    }
}
