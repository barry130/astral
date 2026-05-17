import JSEncrypt from 'jsencrypt';

let cachedPublicKey: string | null = null;

export const getPublicKey = async (): Promise<string> => {
  if (cachedPublicKey) {
    return cachedPublicKey;
  }
  
  try {
    const response = await fetch('/api/v1/auth/public-key');
    if (!response.ok) {
      throw new Error(`HTTP ${response.status}: 后端服务未启动或接口不可用`);
    }
    const result = await response.json();
    
    if (result.code === 200 && result.data?.publicKey) {
      cachedPublicKey = result.data.publicKey;
      return cachedPublicKey!;
    }
    
    throw new Error(result.message || '获取公钥失败');
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error('获取公钥失败：请确保后端服务已启动在 localhost:8080');
  }
};

export const encryptPassword = async (password: string, retry = true): Promise<string> => {
  const publicKey = await getPublicKey();
  
  const encrypt = new JSEncrypt();
  encrypt.setPublicKey(`-----BEGIN PUBLIC KEY-----\n${publicKey}\n-----END PUBLIC KEY-----`);
  
  const encrypted = encrypt.encrypt(password);
  
  if (!encrypted) {
    if (retry) {
      clearPublicKeyCache();
      return encryptPassword(password, false);
    }
    throw new Error('密码加密失败');
  }
  
  return encrypted.replace(/\n/g, '');
};

export const clearPublicKeyCache = () => {
  cachedPublicKey = null;
};
