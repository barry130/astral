package com.astral.system.notify;

import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.controller.NotifyRuleController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackReplyPlatformTest {
    @ParameterizedTest
    @ValueSource(strings = {"feedbackAdminReplied", "feedbackUserReplied"})
    void ruleWriteUsesAllPlatforms(String event) {
        SysNotifyRuleService rules = mock(SysNotifyRuleService.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "notify-rule-test"), SysNotifyRule.class);
        com.astral.dao.mapper.SysNotifyRuleMapper ruleMapper = mock(com.astral.dao.mapper.SysNotifyRuleMapper.class);
        when(ruleMapper.selectCount(any())).thenReturn(0L);
        when(rules.lambdaQuery()).thenAnswer(call ->
                new com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<>(ruleMapper));
        SysMailTemplateMapper templates = mock(SysMailTemplateMapper.class);
        SysMailTemplate template = new SysMailTemplate();
        template.setId(1L);
        template.setScene(event);
        template.setChannel("INAPP");
        when(templates.selectById(1L)).thenReturn(template);
        NotifyRuleController controller = new NotifyRuleController(rules, templates,
                mock(SysSmsTemplateMapper.class), mock(NotifyPublisher.class));
        SysNotifyRule rule = new SysNotifyRule();
        rule.setRuleName("reply");
        rule.setEventCode(event);
        rule.setChannel("INAPP");
        rule.setTemplateId(1L);
        rule.setRecipientType("PAYLOAD_FIELD");
        rule.setRecipientValue("userId");
        // The user-reply event does not declare userId, so use a fixed recipient in that case.
        if (NotifyEventRegistry.FEEDBACK_USER_REPLIED.equals(event)) {
            rule.setRecipientType("FIXED");
            rule.setRecipientValue("7");
        }
        rule.setPlatform("app-windows");
        controller.create(rule);
        assertEquals("all", rule.getPlatform());
        verify(rules).save(rule);
        rule.setPlatform("app-android,app-ios");
        when(rules.getById(1L)).thenReturn(rule);
        controller.update(1L, rule);
        assertEquals("all", rule.getPlatform());
        verify(rules).updateById(rule);
    }

    @ParameterizedTest
    @ValueSource(strings = {"feedbackAdminReplied", "feedbackUserReplied"})
    void deliveryOverridesLegacyPlatformAndRetainsRecipient(String event) {
        SysNotice row = deliver(event, "app-windows");
        assertEquals("all", row.getChannel());
        assertEquals(7L, row.getUserId());
        assertEquals(event, row.getScene());
    }

    @Test
    void unrelatedEventRetainsConfiguredPlatform() {
        assertEquals("app-windows", deliver(NotifyEventRegistry.SYSTEM_ALERT, "app-windows").getChannel());
    }

    @Test
    void roleChangeEventOnlyDeclaresInappAndCompletePayload() {
        NotifyEventDef event = NotifyEventRegistry.find(NotifyEventRegistry.USER_ROLES_CHANGED).orElseThrow();
        assertEquals(java.util.List.of("INAPP"), event.channels());
        assertEquals(java.util.Set.of("userId", "addedRoles", "removedRoles", "changeSummary"),
                java.util.Set.copyOf(event.payloadFieldNames()));
    }

    private SysNotice deliver(String scene, String platform) {
        SysNoticeMapper notices = mock(SysNoticeMapper.class);
        SysMailTemplateMapper templates = mock(SysMailTemplateMapper.class);
        SysMailTemplate template = new SysMailTemplate();
        template.setChannel("INAPP");
        template.setScene(scene);
        template.setSubject("title");
        template.setContent("content");
        when(templates.selectById(1L)).thenReturn(template);
        InappServiceImpl service = new InappServiceImpl(notices, templates);
        service.sendTemplateRow(1L, 7L, Map.of(), platform);
        ArgumentCaptor<SysNotice> captor = ArgumentCaptor.forClass(SysNotice.class);
        verify(notices).insert(captor.capture());
        return captor.getValue();
    }
}
