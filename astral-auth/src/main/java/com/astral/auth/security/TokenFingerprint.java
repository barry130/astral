package com.astral.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 会话令牌指纹。
 *
 * <p><b>为什么需要它</b>：Sa-Token 的 token 值本身就是<b>凭据</b>——拿到它的人可以原样塞进
 * {@code satoken} 请求头冒充该账号（无二次因子）。而管理端「Token 管理」页与个人中心
 * 「我的会话」都需要一个<b>能指认某条会话</b>、又能被前端回传给吊销接口的标识。
 * 直接回传明文 token 等于把全库在线会话的凭据发给每一个「只能查看」的角色：
 * 只要持有 {@code admin:system:token:view} 就能抄走超管的 token 直接接管账号，
 * 该权限的下放前提（只读、无提权）因此不成立。</p>
 *
 * <p><b>做法</b>：对外只暴露 {@code SHA-256(token)} 的前 128 比特（32 个十六进制字符）。
 * 指纹单向、不可反推，也无法当作凭据使用；同一 token 的指纹恒定，因此前端拿它当
 * 吊销键、归属校验键都能正常工作，接口契约不变。真正吊销时由服务端把指纹反查回
 * token 值（扫一次在线会话键，与列表接口同量级）。</p>
 *
 * <p>截断到 128 位而不是全长：2036 年前在线会话数远低于 2^64，生日碰撞概率可忽略，
 * 同时把前端表格列宽控制在可读范围。</p>
 */
public final class TokenFingerprint {

    /** 指纹保留的十六进制字符数（32 字符 = 128 比特） */
    private static final int FINGERPRINT_HEX_LENGTH = 32;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private TokenFingerprint() {}

    /**
     * 计算 token 的指纹。
     *
     * @param token 明文 token；{@code null}/空串返回 {@code null}（调用方按「无会话」处理）
     * @return 32 位小写十六进制指纹
     */
    public static String of(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            // String.format 的 %02x 在循环里开销明显；手写十六进制转换（会话列表会调 N 次）
            char[] out = new char[FINGERPRINT_HEX_LENGTH];
            for (int i = 0; i < FINGERPRINT_HEX_LENGTH / 2; i++) {
                int b = hash[i] & 0xFF;
                out[i * 2] = HEX[b >>> 4];
                out[i * 2 + 1] = HEX[b & 0x0F];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 强制实现的算法，走不到这里；抛出去也比静默降级成明文安全
            throw new IllegalStateException("SHA-256 不可用，无法生成会话指纹", e);
        }
    }

    /**
     * 判断给定字符串是否形如指纹（32 位小写十六进制）。
     *
     * <p>仅用于入参形态自检：吊销接口拿到的不应是明文 token，若形态不符直接拒绝，
     * 避免调用方把凭据写进 URL（日志）后还以为在被使用。</p>
     *
     * @param value 待判断字符串
     * @return 形如指纹返回 {@code true}
     */
    public static boolean looksLikeFingerprint(String value) {
        if (value == null || value.length() != FINGERPRINT_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
