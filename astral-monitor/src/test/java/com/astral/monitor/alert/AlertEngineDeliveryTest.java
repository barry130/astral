package com.astral.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.astral.dao.entity.AlertChannel;
import com.astral.dao.entity.AlertRecord;
import com.astral.dao.entity.AlertRule;
import com.astral.dao.mapper.AlertChannelMapper;
import com.astral.dao.mapper.AlertRecordMapper;
import com.astral.dao.mapper.AlertRuleMapper;
import com.astral.dao.mapper.StatApiHourlyMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import com.astral.system.mail.MailService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

class AlertEngineDeliveryTest {
    @ParameterizedTest
    @ValueSource(strings = {"EMAIL", "SMS", "WEBHOOK"})
    @SuppressWarnings("unchecked")
    void disabledChannelRecordsFailureAndCooldownWithoutDelivery(String type) {
        var rules = mock(AlertRuleMapper.class);
        var channels = mock(AlertChannelMapper.class);
        var records = mock(AlertRecordMapper.class);
        var errors = mock(StatErrorLogMapper.class);
        var rule = new AlertRule();
        rule.setId(1L);
        rule.setChannelId(2L);
        rule.setMetric(AlertEngine.METRIC_SERVER_ERROR);
        rule.setThreshold(1L);
        when(rules.selectList(any())).thenReturn(List.of(rule));
        when(errors.selectCount(any())).thenReturn(2L);
        var channel = new AlertChannel();
        channel.setId(2L);
        channel.setType(type);
        channel.setEnabled(0);
        channel.setConfig("{\"to\":\"fixture@example.test\",\"phone\":\"fixture\",\"url\":\"https://example.test\"}");
        when(channels.selectById(2L)).thenReturn(channel);
        ObjectProvider<MailService> mail = mock(ObjectProvider.class);
        ObjectProvider<com.astral.system.notify.SmsService> sms = mock(ObjectProvider.class);
        var policy = mock(WebhookUrlPolicy.class);
        var engine =
                new AlertEngine(rules, channels, records, errors, mock(StatApiHourlyMapper.class), mail, sms, policy);
        engine.evaluate();
        verifyNoInteractions(mail, sms, policy);
        var saved = ArgumentCaptor.forClass(AlertRecord.class);
        verify(records).insert(saved.capture());
        assertEquals("FAIL", saved.getValue().getStatus());
        assertEquals("绑定渠道已停用", saved.getValue().getErrorMsg());
        assertNotNull(rule.getLastFiredAt());
    }
}
