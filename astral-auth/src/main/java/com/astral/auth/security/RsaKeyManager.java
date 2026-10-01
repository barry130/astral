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

@Slf4j
@Component
public class RsaKeyManager {

    private PublicKey publicKey;
    private PrivateKey privateKey;

    /** 登录失败计数 / 锁定：Redis 存储（内存兜底），见 {@link LoginFailureStore} */
    private final LoginFailureStore loginFailureStore;

    public RsaKeyManager(LoginFailureStore loginFailureStore) {
        this.loginFailureStore = loginFailureStore;
    }

    /** 密钥文件路径，可通过配置覆盖 */
    @Value("${astral.auth.rsa-key-path:./data/rsa-key.pair}")
    private String keyPath;

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
            // 显式指定字符集：new String(byte[]) 依赖 JVM 默认字符集（Charset.defaultCharset()），
            // 容器里若不是 UTF-8（如某些镜像默认 ISO-8859-1），解密出的密码会乱码 → 登录永远失败。
            return new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            log.error("Base64解码失败: {}", e.getMessage());
            throw new GeneralSecurityException("密码格式错误", e);
        } catch (GeneralSecurityException e) {
            log.error("RSA解密失败: {}", e.getMessage());
            throw e;
        }
    }

    public void recordLoginFailure(String username) {
        loginFailureStore.recordFailure(username);
    }

    public void resetLoginFailures(String username) {
        loginFailureStore.reset(username);
    }

    public boolean isAccountLocked(String username) {
        return loginFailureStore.isLocked(username);
    }

    public int getRemainingFailures(String username) {
        return loginFailureStore.remainingFailures(username);
    }
}
