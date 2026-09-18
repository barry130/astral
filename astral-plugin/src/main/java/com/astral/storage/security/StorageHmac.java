package com.astral.storage.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * storage HMAC 工具
 * <p>
 * 所有签名使用 HMAC-SHA256 + Base64URL（无填充），校验使用常量时间比较。
 * 规范串规则与 Cloudflare Worker（cloudflare/storage-worker）保持逐字节一致：
 * <ul>
 *   <li>Worker → Astral 服务认证：{@code METHOD\nPATH\nTIMESTAMP\nNONCE}</li>
 *   <li>下载 URL 签名：{@code GET\n/f/{publicId}/{contentVersion}\n{expiresEpoch}\n{keyVersion}}</li>
 *   <li>上传凭证：对 base64url(payload) 整体签名，ticket = payloadB64 + "." + sig</li>
 * </ul>
 * 任何一侧调整规范串都必须同步修改 Worker 并更新固定测试向量。
 * </p>
 */
public final class StorageHmac {

    private StorageHmac() {
    }

    /** 计算 HMAC-SHA256 并返回 Base64URL（无填充）签名 */
    public static String sign(byte[] key, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    /** 常量时间校验签名 */
    public static boolean verify(byte[] key, String canonical, String signatureB64Url) {
        if (key == null || key.length == 0 || signatureB64Url == null || signatureB64Url.isBlank()) {
            return false;
        }
        try {
            byte[] actual = sign(key, canonical).getBytes(StandardCharsets.US_ASCII);
            byte[] provided = signatureB64Url.getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(actual, provided);
        } catch (Exception e) {
            return false;
        }
    }

    /** SHA-256 十六进制摘要（预留：请求体绑定等场景） */
    public static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    /** Base64URL 编码（无填充） */
    public static String b64UrlEncode(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    /** Base64URL 解码（兼容带/不带填充） */
    public static byte[] b64UrlDecode(String data) {
        String normalized = data.replace('-', '+').replace('_', '/');
        int pad = (4 - normalized.length() % 4) % 4;
        return Base64.getDecoder().decode(normalized + "=".repeat(pad));
    }
}
