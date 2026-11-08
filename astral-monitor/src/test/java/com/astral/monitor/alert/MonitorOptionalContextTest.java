package com.astral.monitor.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.astral.dao.mapper.AlertChannelMapper;
import com.astral.dao.mapper.AlertRecordMapper;
import com.astral.dao.mapper.AlertRuleMapper;
import com.astral.dao.mapper.StatApiHourlyMapper;
import com.astral.dao.mapper.StatErrorLogMapper;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MonitorOptionalContextTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statisticsSwitchControlsTheEngineAndItsManagementApi(boolean enabled) {
        new ApplicationContextRunner()
                .withUserConfiguration(AlertEngine.class, AlertAdminController.class)
                .withBean(AlertRuleMapper.class, () -> mock(AlertRuleMapper.class))
                .withBean(AlertChannelMapper.class, () -> mock(AlertChannelMapper.class))
                .withBean(AlertRecordMapper.class, () -> mock(AlertRecordMapper.class))
                .withBean(StatErrorLogMapper.class, () -> mock(StatErrorLogMapper.class))
                .withBean(StatApiHourlyMapper.class, () -> mock(StatApiHourlyMapper.class))
                .withBean(WebhookUrlPolicy.class, () -> new WebhookUrlPolicy("", false))
                .withPropertyValues("astral.stat.enabled=" + enabled)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.containsBean("alertEngine")).isEqualTo(enabled);
                    assertThat(context.getBeansOfType(AlertAdminController.class))
                            .hasSize(enabled ? 1 : 0);
                });
    }

    @Test
    void metricNamesDoNotDependOnTheServerLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(AlertEngine.normalizeMetric("client_error_count")).isEqualTo("CLIENT_ERROR_COUNT");
        } finally {
            Locale.setDefault(previous);
        }
    }
}
