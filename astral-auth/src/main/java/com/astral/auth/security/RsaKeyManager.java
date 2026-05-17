package com.astral.auth.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.security.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RsaKeyManager {

    private PublicKey publicKey;
    private PrivateKey privateKey;

    private final ConcurrentHashMap<String, Long> loginFailures = new ConcurrentHashMap<>();
    private static final int MAX_FAILURES = 5;
    private static final long LOCK_DURATION_MS = 15 * 60 * 1000;

    @PostConstruct
    public void init() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        this.publicKey = keyPair.getPublic();
        this.privateKey = keyPair.getPrivate();
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
        loginFailures.put(username, System.currentTimeMillis());
    }

    public void resetLoginFailures(String username) {
        loginFailures.remove(username);
    }

    public boolean isAccountLocked(String username) {
        Long failureTime = loginFailures.get(username);
        if (failureTime == null) {
            return false;
        }
        long elapsed = System.currentTimeMillis() - failureTime;
        if (elapsed > LOCK_DURATION_MS) {
            loginFailures.remove(username);
            return false;
        }
        return true;
    }

    public int getRemainingFailures(String username) {
        Long failureTime = loginFailures.get(username);
        if (failureTime == null) {
            return MAX_FAILURES;
        }
        long elapsed = System.currentTimeMillis() - failureTime;
        if (elapsed > LOCK_DURATION_MS) {
            loginFailures.remove(username);
            return MAX_FAILURES;
        }
        return 0;
    }
}
