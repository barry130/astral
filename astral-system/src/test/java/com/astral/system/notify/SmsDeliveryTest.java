package com.astral.system.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.common.exception.BusinessException;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.notify.sms.SmsProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmsDeliveryTest {
    @Test
    void maximumWeightsKeepSmsFailoverFiniteAndTryEachProviderOnce() {
        var candidates = new ArrayList<SysSmsProvider>();
        for (long id = 1; id <= 3; id++) {
            var candidate = new SysSmsProvider();
            candidate.setId(id);
            candidate.setWeight(Integer.MAX_VALUE);
            candidate.setProviderType("FIXTURE");
            candidates.add(candidate);
        }
        var providers = mock(SysSmsProviderMapper.class);
        when(providers.selectList(any())).thenReturn(candidates);
        var templates = mock(SysSmsTemplateMapper.class);
        when(templates.selectById(1L)).thenReturn(new SysSmsTemplate());
        var attempted = new ArrayList<Long>();
        SmsProvider delivery = new SmsProvider() {
            @Override
            public String type() {
                return "FIXTURE";
            }

            @Override
            public void send(
                    SysSmsProvider provider, String phone, String sign, String code, Map<String, String> variables) {
                attempted.add(provider.getId());
                throw new IllegalStateException("fixture failure");
            }
        };
        var service = new SmsServiceImpl(List.of(delivery), providers, templates, mock(SysSmsLogMapper.class));
        assertTimeout(
                Duration.ofSeconds(3),
                () -> assertThrows(
                        BusinessException.class, () -> service.testSendTemplate(1L, null, "fixture-phone", Map.of())));
        assertEquals(3, attempted.size());
        assertEquals(3, attempted.stream().distinct().count());
    }
}
