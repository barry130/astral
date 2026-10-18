package com.astral.common.error;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Properties;

public class ErrorCodes {

    private static final Properties PROPERTIES = new Properties();

    static {
        try (InputStream input = ErrorCodes.class.getClassLoader().getResourceAsStream("error-codes.properties")) {
            if (input != null) {
                PROPERTIES.load(new InputStreamReader(input, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new RuntimeException("加载错误码文件失败", e);
        }
    }

    public static String getMessage(String code) {
        return PROPERTIES.getProperty(code, "未知错误");
    }

    public static String format(String code, Object... args) {
        String template = getMessage(code);
        if (args == null || args.length == 0) {
            return template;
        }
        String formatted = MessageFormat.format(template, args);
        // 文案没有占位符时 MessageFormat 会静默丢弃细节参数，导致「密钥口令未配置」
        // 这类关键原因全部被压成同一句提示，因此把细节附在消息尾部。
        if (template.indexOf("{0}") < 0 && !formatted.equals(String.valueOf(args[0]))) {
            formatted = formatted + "：" + args[0];
        }
        return formatted;
    }
}
