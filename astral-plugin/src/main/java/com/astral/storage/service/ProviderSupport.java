package com.astral.storage.service;

import com.astral.storage.entity.StorageConfigEntity;

/**
 * 存储 Provider 能力归类：直传/删除/校验分支按家族收敛，
 * 新增 Provider 只需在此登记常量与家族归属。
 */
public final class ProviderSupport {

    private ProviderSupport() {
    }

    /** S3 SigV4 家族（AWS Signature V4 + 预签名 URL 直传） */
    public static boolean isS3Family(String providerType) {
        return StorageConfigEntity.PROVIDER_R2.equals(providerType)
                || StorageConfigEntity.PROVIDER_S3.equals(providerType)
                || StorageConfigEntity.PROVIDER_QINIU.equals(providerType);
    }

    /** 浏览器 PUT 预签名 URL 直传家族（S3/COS/OSS） */
    public static boolean isPresignedPutFamily(String providerType) {
        return isS3Family(providerType)
                || StorageConfigEntity.PROVIDER_COS.equals(providerType)
                || StorageConfigEntity.PROVIDER_OSS.equals(providerType);
    }

    /** 需要浏览器回执登记（上传完成后 POST /files/register）的家族 */
    public static boolean isRegisterFamily(String providerType) {
        return isPresignedPutFamily(providerType)
                || StorageConfigEntity.PROVIDER_UPYUN.equals(providerType);
    }

    /** 服务端直接操作对象（HEAD/DELETE 同步执行）的家族（除 TELEGRAM 外全部） */
    public static boolean isServerManagedFamily(String providerType) {
        return isRegisterFamily(providerType);
    }

    /** 支持永久公开链接（publicBaseUrl + 对象键）的家族 */
    public static boolean isPermanentUrlFamily(String providerType) {
        return isRegisterFamily(providerType);
    }

    public static boolean isKnown(String providerType) {
        return StorageConfigEntity.PROVIDER_TELEGRAM.equals(providerType)
                || isRegisterFamily(providerType);
    }
}
