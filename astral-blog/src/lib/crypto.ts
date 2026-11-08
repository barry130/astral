import { constants, createPublicKey, publicEncrypt } from 'node:crypto';

export class PasswordEncodingTooLong extends Error {}

/**
 * 用后端 RSA 公钥加密登录密码。
 *
 * 后端 astral-auth 的 RsaKeyManager 用 `Cipher.getInstance("RSA")`（PKCS#1 v1.5 填充）
 * 私钥解密，所以这里必须用 RSA_PKCS1_PADDING 与之配对（不能用 OAEP）。
 * 公钥由 `GET /api/v1/all/auth/public-key` 以 Base64 的 X.509 形式给出，需包成 PEM。
 */
export function encryptPassword(password: string, publicKeyBase64: string): string {
  if (!publicKeyBase64 || publicKeyBase64.length > 16_384)
    throw new Error('Invalid login public key');
  const key = createPublicKey(
    `-----BEGIN PUBLIC KEY-----\n${publicKeyBase64}\n-----END PUBLIC KEY-----`,
  );
  const bits = key.asymmetricKeyDetails?.modulusLength;
  if (key.asymmetricKeyType !== 'rsa' || !bits || bits < 2048 || bits > 8192)
    throw new Error('Invalid login RSA key');
  const encoded = Buffer.from(password, 'utf8');
  if (encoded.length > Math.floor(bits / 8) - 11) throw new PasswordEncodingTooLong();
  return publicEncrypt({ key, padding: constants.RSA_PKCS1_PADDING }, encoded).toString('base64');
}
