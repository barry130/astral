package com.astral.auth.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

/**
 * TOTP（RFC 6238）工具：时间步 30s、HMAC-SHA1、6 位动态码，允许 ±1 步时钟偏移。
 *
 * <p>纯 JDK 实现，不引第三方依赖。密钥以 Base32（RFC 4648）存取，
 * otpauth URI 供前端渲染二维码（Google Authenticator 等任何 TOTP 应用均可绑定）。</p>
 */
public final class TotpUtil {

    /** 时间步长（秒） */
    private static final long STEP_SECONDS = 30;
    /** 允许的时钟偏移步数：验证当前步与前后各一步 */
    private static final int WINDOW_STEPS = 1;
    /** 动态码位数 */
    private static final int CODE_DIGITS = 6;
    /** 密钥字节数（160bit，与主流验证器 App 兼容） */
    private static final int SECRET_BYTES = 20;

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private static final SecureRandom RANDOM = new SecureRandom();

    private TotpUtil() {
    }

    /** 生成随机密钥（Base32 编码，无填充） */
    public static String generateSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return base32Encode(bytes);
    }

    /**
     * 校验动态码
     *
     * @param code   用户输入的 6 位数字
     * @param secret Base32 密钥
     * @return true = 校验通过（当前步或 ±1 步内命中）
     */
    public static boolean verify(String code, String secret) {
        if (code == null || secret == null) {
            return false;
        }
        String normalized = code.trim();
        if (normalized.length() != CODE_DIGITS || !normalized.chars().allMatch(Character::isDigit)) {
            return false;
        }
        byte[] key = base32Decode(secret);
        if (key == null || key.length == 0) {
            return false;
        }
        long currentStep = System.currentTimeMillis() / 1000 / STEP_SECONDS;
        int inputCode;
        try {
            inputCode = Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return false;
        }
        for (int i = -WINDOW_STEPS; i <= WINDOW_STEPS; i++) {
            if (hotp(key, currentStep + i) == inputCode) {
                return true;
            }
        }
        return false;
    }

    /** 当前动态码（仅测试/排障用） */
    public static String currentCode(String secret) {
        byte[] key = base32Decode(secret);
        return String.format("%0" + CODE_DIGITS + "d", hotp(key, System.currentTimeMillis() / 1000 / STEP_SECONDS));
    }

    /**
     * 生成 otpauth:// 绑定 URI（前端用它渲染二维码）
     *
     * @param secret   Base32 密钥
     * @param accountName 展示用的账号标识（用户名）
     * @param issuer   发行方标识（应用名）
     */
    public static String buildOtpAuthUri(String secret, String accountName, String issuer) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + secret
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + CODE_DIGITS + "&period=" + STEP_SECONDS;
    }

    /** RFC 4226 HOTP：HMAC-SHA1 截断取模 */
    private static int hotp(byte[] key, long step) {
        byte[] message = ByteBuffer.allocate(8).putLong(step).array();
        Mac mac;
        try {
            mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA1 不可用", e);
        }
        byte[] hash = mac.doFinal(message);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        return binary % (int) Math.pow(10, CODE_DIGITS);
    }

    private static String base32Encode(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : bytes) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >> (bits - 5)) & 0x1F));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String input) {
        if (input == null) {
            return null;
        }
        String normalized = input.trim().replace("=", "").replace(" ", "").toUpperCase();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : normalized.toCharArray()) {
            int value = BASE32_ALPHABET.indexOf(c);
            if (value < 0) {
                return null;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xFF);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return value == null ? "" : java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
