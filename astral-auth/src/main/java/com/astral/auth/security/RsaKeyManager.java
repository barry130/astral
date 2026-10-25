package com.astral.auth.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.security.spec.MGF1ParameterSpec;
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

    /** 新客户端应使用的填充模式（前端需同步改为 RSA-OAEP + SHA-256） */
    public static final String OAEP_TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

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
                restrictToOwner(path);
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
            restrictToOwner(path);
            log.info("RSA密钥对已持久化到文件: {}", path);
        } catch (IOException e) {
            log.warn("RSA密钥对持久化失败（不影响运行，仅每次重启变化）: {}", e.getMessage());
        }
        log.info("RSA密钥对初始化成功");
    }

    /**
     * 私钥文件收紧到「仅属主可读写」（POSIX {@code rw-------}）。
     *
     * <p>默认 umask（022）下新建文件是 644，同机任何本地用户都能读走走登录传输层的私钥。
     * 非 POSIX 文件系统（Windows）不支持时忽略，不影响启动。容器里由命名卷继承镜像属主，
     * 属主即运行用户，收紧后仍可正常读写。</p>
     */
    private void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path,
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            log.debug("当前文件系统不支持 POSIX 权限，跳过私钥文件收紧: {}", path);
        } catch (IOException e) {
            log.warn("私钥文件权限收紧失败（请手工 chmod 600）: {} - {}", path, e.getMessage());
        }
    }

    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 登录密码密文解密。
     *
     * <p><b>为什么优先 OAEP</b>：此前用 {@code Cipher.getInstance("RSA")}（即
     * {@code RSA/ECB/PKCS1Padding}）。PKCS#1 v1.5 的解填充错误与「密码错误」是两个不同的
     * 可观察结果（{@code AUTH004} vs {@code AUTH002}），组合起来构成经典的 Bleichenbacher
     * 式填充 oracle；配合「用户名不存在也走同一条路径」，可被用来做密文/账号的判别攻击。</p>
     *
     * <p><b>为什么保留 PKCS#1 回退</b>：已发布的客户端（轻听 App、第三方脚本、Swagger）
     * 仍按 PKCS#1 v1.5 加密。直接把前端切到 OAEP 会让它们集体登录失败，所以这里
     * 「先按 OAEP 解，失败再按 PKCS#1 解」——新客户端用 OAEP 即自动走上更安全的路径，
     * 旧客户端保持可用。两条路径的失败在上层被统一成一个错误码（不再区分），
     * oracle 的可观察差异因此消失。</p>
     */
    public byte[] decryptPassword(byte[] encryptedPassword) throws GeneralSecurityException {
        try {
            Cipher cipher = Cipher.getInstance(OAEP_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey,
                    new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
            return cipher.doFinal(encryptedPassword);
        } catch (GeneralSecurityException oaepFailure) {
            // 兼容旧客户端：PKCS#1 v1.5
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            return cipher.doFinal(encryptedPassword);
        }
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
