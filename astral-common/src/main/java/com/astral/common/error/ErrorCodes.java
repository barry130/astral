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
        return MessageFormat.format(getMessage(code), args);
    }
}
