package com.astral.system.notify.sms;

import java.util.Map;

import com.astral.dao.entity.SysSmsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 本地测试/联调用空跑供应商：不发真实短信，只记日志即成功。
 * 让短信链路在没有真实云凭据的环境（本地、CI）可全流程验证。
 */
@Slf4j
@Component
public class MockSmsProvider implements SmsProvider {

    @Override
    public String type() {
        return "MOCK";
    }

    @Override
    public void send(SysSmsProvider provider, String phone, String signName, String templateCode, Map<String, String> params) {
        log.info("[Sms][MOCK] provider={} phone={} sign={} template={} params={}",
                provider.getProviderName(), phone, signName, templateCode, params);
    }
}
