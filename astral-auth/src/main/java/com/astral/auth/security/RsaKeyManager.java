package com.astral.auth.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RsaKeyManager {

    private PublicKey publicKey;
    private PrivateKey privateKey;

    private final ConcurrentHashMap<String, FailureRecord> loginFailures = new ConcurrentHashMap<>();
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_DURATION_MS = 15 * 60 * 1000;
    private static final long FAILURE_WINDOW_MS = 15 * 60 * 1000;

    /** 密钥文件路径，可通过配置覆盖 */
    @Value("${astral.auth.rsa-key-path:./data/rsa-key.pair}")
    private String keyPath;

    private static class FailureRecord {
        int count;
        long lastFailureTime;
        long lockUntil;
    }

    @PostConstruct
    public void init() throws Exception {
        Path path = Paths.get(keyPath).toAbsolutePath().normalize();
        if (Files.exists(path)) {
            // 从文件加载密钥对
            byte[] encoded = Files.readAllBytes(path);
            String parts[] = new String(encoded, "UTF-8").split("\\|");
            if (parts.length == 2) {
                KeyFactory kf = KeyFactory.getInstance("RSA");
                this.publicKey = kf.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(parts[0])));
                this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(parts[1])));
                log.info("RSA密钥对从文件加载成功: {}", path);
            } else {
                generateAndSave(path);
            }
        } else {
            generateAndSave(path);
        }
    }

    private void generateAndSave(Path path) throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        this.publicKey = keyPair.getPublic();
        this.privateKey = keyPair.getPrivate();

        // 持久化到文件
        try {
            Files.createDirectories(path.getParent());
            String encoded = Base64.getEncoder().encodeToString(publicKey.getEncoded())
                    + "|" + Base64.getEncoder().encodeToString(privateKey.getEncoded());
            Files.writeString(path, encoded);
            log.info("RSA密钥对已持久化到文件: {}", path);
        } catch (IOException e) {
            log.warn("RSA密钥对持久化失败（不影响运行，仅每次重启变化）: {}", e.getMessage());
        }
        log.info("RSA密钥对初始化成功");
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public byte[] decryptPassword(byte[] encryptedPassword) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA");
        cipher.init(Cipher.DECRYPT_MODE, privateKey);
        return cipher.doFinal(encryptedPassword);
    }

    public String decryptPasswordBase64(String encryptedPasswordBase64) throws GeneralSecurityException {
        try {
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptedPasswordBase64);
            byte[] decryptedBytes = decryptPassword(encryptedBytes);
            return new String(decryptedBytes);
        } catch (IllegalArgumentException e) {
            log.error("Base64解码失败: {}", e.getMessage());
            throw new GeneralSecurityException("密码格式错误", e);
        } catch (GeneralSecurityException e) {
            log.error("RSA解密失败: {}", e.getMessage());
            throw e;
        }
    }

    public void recordLoginFailure(String username) {
        loginFailures.compute(username, (key, record) -> {
            long now = System.currentTimeMillis();
            if (record == null) {
                record = new FailureRecord();
            }
            if (now - record.lastFailureTime > FAILURE_WINDOW_MS) {
                record.count = 0;
            }
            record.count++;
            record.lastFailureTime = now;
            if (record.count >= MAX_FAILURES) {
                record.lockUntil = now + LOCK_DURATION_MS;
            }
            return record;
        });
    }

    public void resetLoginFailures(String username) {
        loginFailures.remove(username);
    }

    public boolean isAccountLocked(String username) {
        FailureRecord record = loginFailures.get(username);
        if (record == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (record.lockUntil > 0 && now < record.lockUntil) {
            return true;
        }
        if (now - record.lastFailureTime > FAILURE_WINDOW_MS) {
            loginFailures.remove(username);
            return false;
        }
        return false;
    }

    public int getRemainingFailures(String username) {
        FailureRecord record = loginFailures.get(username);
        if (record == null) {
            return MAX_FAILURES;
        }
        long now = System.currentTimeMillis();
        if (record.lockUntil > 0 && now < record.lockUntil) {
            return 0;
        }
        if (now - record.lastFailureTime > FAILURE_WINDOW_MS) {
            loginFailures.remove(username);
            return MAX_FAILURES;
        }
        return Math.max(0, MAX_FAILURES - record.count);
    }
}
