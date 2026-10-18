package com.astral.ai.security;

import com.astral.ai.common.AiException;
import com.astral.ai.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AI 提供方密钥加解密。
 *
 * <p>设计文档 §5.2 要求 API Key 加密存储、永不回显。这里用 AES-256-GCM：
 * KEK 由 {@code astral.plugins.ai.secret-key} 经 SHA-256 派生，密文格式为
 * {@code v1:base64(iv):base64(ciphertext+tag)}。</p>
 *
 * <p>未配置 KEK 时 {@link #isConfigured()} 为 false，此时只允许使用 {@code secret_ref}
 * （由部署环境注入的环境变量名），绝不明文落库——这是「宁可不可用，也不泄露」的取舍。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final AiProperties aiProperties;

    private final SecureRandom secureRandom = new SecureRandom();

    /** 是否已配置可用的加密口令 */
    public boolean isConfigured() {
        String key = aiProperties.getSecretKey();
        return key != null && !key.isBlank();
    }

    /**
     * 加密明文密钥。
     *
     * @param plain 明文密钥
     * @return 密文串；口令未配置时抛 AI006（不允许明文落库）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) {
            return null;
        }
        if (!isConfigured()) {
            throw new AiException("AI006", "未配置 astral.plugins.ai.secret-key，无法加密 API Key；"
                    + "请用环境变量 ASTRAL_AI_SECRET_KEY 注入，或改填「密钥引用」指向环境变量");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
                    + ":"
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            log.error("[AiSecretCipher] 加密失败", e);
            throw new AiException("AI006", "API Key 加密失败：" + e.getClass().getSimpleName());
        }
    }

    /**
     * 解密密文。
     *
     * @param stored 数据库中的密文
     * @return 明文密钥；无法解密时返回 null（调用方按「凭证缺失」处理）
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        if (!stored.startsWith(PREFIX)) {
            log.warn("[AiSecretCipher] 密文格式不可识别，按缺失处理");
            return null;
        }
        if (!isConfigured()) {
            log.warn("[AiSecretCipher] 未配置 secret-key，无法解密已存密钥");
            return null;
        }
        try {
            String body = stored.substring(PREFIX.length());
            int sep = body.indexOf(':');
            if (sep <= 0) {
                return null;
            }
            byte[] iv = Base64.getUrlDecoder().decode(body.substring(0, sep));
            byte[] data = Base64.getUrlDecoder().decode(body.substring(sep + 1));
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[AiSecretCipher] 解密失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 {@code secret_ref}：支持 {@code env:NAME} 与裸环境变量名两种写法。
     *
     * @param secretRef 引用串
     * @return 引用到的值；未设置返回 null
     */
    public String resolveSecretRef(String secretRef) {
        if (secretRef == null || secretRef.isBlank()) {
            return null;
        }
        String name = secretRef.startsWith("env:") ? secretRef.substring(4) : secretRef;
        name = name.trim();
        if (name.isEmpty()) {
            return null;
        }
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    /** 描述密钥来源，供接口回显（不含任何密钥内容） */
    public String describe(String encryptedApiKey, String secretRef) {
        if (encryptedApiKey != null && !encryptedApiKey.isBlank()) {
            return "ENCRYPTED";
        }
        if (secretRef != null && !secretRef.isBlank()) {
            return "SECRET_REF";
        }
        return "NONE";
    }

    private SecretKeySpec deriveKey() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = digest.digest(aiProperties.getSecretKey().getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(keyBytes, "AES");
    }

    /** 常量时间比较，供回调校验等场景复用 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /** 计算 HMAC-SHA256 的十六进制串（供幂等键、请求指纹使用） */
    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte value : out) {
                sb.append(Character.forDigit((value >> 4) & 0xF, 16));
                sb.append(Character.forDigit(value & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
