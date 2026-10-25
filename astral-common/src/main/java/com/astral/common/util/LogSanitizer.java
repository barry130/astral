package com.astral.common.util;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏：把即将落库/落盘的 JSON 文本里的凭据字段打码。
 *
 * <p><b>为什么需要</b>：操作日志切面会把请求体与响应体整段 JSON 持久化到
 * {@code sys_operate_log.request_params / response_result}。改密码、重置密码、
 * 邮箱授权码、第三方密钥、令牌都从这些 body 里经过 —— 也就是说，只要持有
 * <em>日志查看</em>权限就能读到明文凭据，鉴权层级被绕开了。业务接口本身
 * 早已把凭据从出参里摘掉（如邮件账号的 SMTP 授权码），日志这一路必须同等对待。</p>
 *
 * <p><b>为什么用正则而不是反序列化</b>：切面里对任意 DTO / Map / 分页对象序列化，
 * 形状不可控（还有可能超长）。用「键名 + 冒号 + 值」的模式替换对所有形状都成立，
 * 且不会因某个字段反序列化失败就整条日志丢失。</p>
 *
 * <p>本类无依赖、纯函数，便于单测（见 {@code LogSanitizerTest}）。</p>
 */
public final class LogSanitizer {

    /** 打码后的替换文本 */
    public static final String MASK = "******";

    /**
     * 需要打码的键名（小写、去掉下划线与连字符后比较）。
     * <p>宁可多打几个码（如 {@code maxTokens} 之类不含凭据语义的键被误伤也无害），
     * 不可漏：漏一个就是一条明文凭据留在库里。</p>
     */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "password", "passwd", "pwd", "oldpassword", "newpassword", "confirmpassword",
            "passwordhash", "rawpassword",
            "token", "accesstoken", "refreshtoken", "satoken", "idtoken", "authorization",
            "secret", "secretkey", "secretid", "secretaccesskey", "accesskeysecret",
            "accesskey", "accesskeyid", "operatorpassword", "tokenkey",
            "apikey", "appsecret", "privatekey", "credential", "credentials",
            "signature", "csrf", "csrftoken", "ticket", "totpsecret", "totpcode");

    /**
     * JSON 对象成员：{@code "key": value}。
     * <p>键名从字母开头，后续允许字母/数字/下划线/连字符/点（覆盖 {@code sql.password} 这类
     * JsonPath 风格）。值分三支：带引号字符串、裸值（数字/布尔/null）、以及<b>不含引号的
     * 容器</b>（{@code [..]} / {@code {..}}）。第三支必须显式排除引号 —— 否则
     * {@code "tokens": [{"accessToken": ...}]} 会被整段当成 tokens 的「值」，
     * 打码时连同后面的所有成员一起吞掉，日志变成不可读的碎片。</p>
     */
    private static final Pattern JSON_PAIR = Pattern.compile(
            "([\"']([A-Za-z][A-Za-z0-9_\\-.]{0,63})[\"'])\\s*:\\s*"
                    + "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'"
                    + "|[^,}\\]\"'\\s]+)",
            Pattern.DOTALL);

    /** 表单/查询串形态（{@code password=xxx&foo=1}） */
    private static final Pattern FORM_PAIR = Pattern.compile(
            "((?:^|[?&])([A-Za-z][A-Za-z0-9_\\-.]{0,63})=)([^&\\s]*)");

    private LogSanitizer() {
    }

    /**
     * 对一段（可能很长的）JSON / 表单文本做凭据脱敏。
     *
     * @param text 原始文本，可为 null
     * @return 脱敏后的文本；入参为 null 时原样返回 null
     */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = JSON_PAIR.matcher(text).replaceAll(match -> {
            // 已打码的值直接跳过，保证 mask 幂等（便于在多层链路上重复调用）。
            // 这里必须比 startsWith 更宽：打码后是 `"******""accessToken"...`，
            // 下一个成员会被并进同一个值里，只判前缀等会让幂等性失效。
            if (!isSensitiveKey(match.group(2)) || match.group(3).contains(MASK)) {
                return Matcher.quoteReplacement(match.group(0));
            }
            // 统一替换为带引号的字面量：目标是「不可读」，不追求打码后仍是合法 JSON。
            return Matcher.quoteReplacement(match.group(1) + ": \"" + MASK + "\"");
        });
        return FORM_PAIR.matcher(masked).replaceAll(match -> {
            if (!isSensitiveKey(match.group(2)) || match.group(3).startsWith(MASK)) {
                return Matcher.quoteReplacement(match.group(0));
            }
            return Matcher.quoteReplacement(match.group(1) + MASK);
        });
    }

    /**
     * 键名里含这些词就视为凭据（用于识别 {@code smtpPassword / dbPassword / mailAuthToken}
     * 这类「前缀只是一个名词」的复合键）。单独成表是因为精确匹配永远列不完组合。
     */
    private static final String[] SENSITIVE_TOKENS = {
            "password", "passwd", "pwd", "secret", "token", "apikey", "credential", "signature"
    };

    /**
     * 是否属于需要打码的键名（供其他链路复用同一套判定）。
     *
     * @param key 原始键名
     * @return 命中返回 true
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = normalizeKey(key);
        if (SENSITIVE_KEYS.contains(normalized)) {
            return true;
        }
        for (String token : SENSITIVE_TOKENS) {
            if (normalized.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /** 归一键名：小写 + 去掉下划线/连字符/空白，使 {@code access_key} 与 {@code accessKey} 等价 */
    private static String normalizeKey(String key) {
        StringBuilder sb = new StringBuilder(key.length());
        for (char c : key.toCharArray()) {
            if (c == '_' || c == '-' || Character.isWhitespace(c)) {
                continue;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
