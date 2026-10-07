package com.astral.system.notify.sms;

import java.util.Map;

import com.astral.dao.entity.SysSmsProvider;

/**
 * 短信供应商 SPI
 *
 * <p>实现类声明为 Spring {@code @Component}，由 {@code SmsServiceImpl} 按
 * {@link #type()} 聚合路由（sys_sms_provider.provider_type）。
 * 新增供应商（腾讯云/华为云等）只需实现本接口，无需动发送链路。</p>
 */
public interface SmsProvider {

    /** 供应商类型标识（对应 sys_sms_provider.provider_type，如 MOCK / ALIYUN） */
    String type();

    /**
     * 发送短信（供应商模板语义：正文在供应商侧备案，这里只传模板编码与按名透传的变量）
     *
     * @param provider     供应商配置（含密钥/endpoint；签名经 signName 显式传入，支持模板级覆盖）
     * @param phone        手机号
     * @param signName     生效签名（模板级覆盖供应商级）
     * @param templateCode 供应商侧模板编码（如阿里云 SMS_123456789）
     * @param params       模板变量（按名透传，JSON 序列化后作为模板参数）
     * @throws Exception 网络/签名/供应商业务错误（Code != OK 等）
     */
    void send(SysSmsProvider provider, String phone, String signName, String templateCode, Map<String, String> params) throws Exception;
}
