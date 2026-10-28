package com.astral.system.notify;

import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysNotifyRuleChannel;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.system.controller.NotifyRuleController;
import com.astral.system.dto.NotifyRuleSaveDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackReplyPlatformTest {

    @ParameterizedTest
    @ValueSource(strings = {"feedbackAdminReplied", "feedbackUserReplied"})
    void ruleWriteForcesAllPlatformsForReplyEvents(String event) {
        SysNotifyRuleService rules = mock(SysNotifyRuleService.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "notify-rule-test"), SysNotifyRule.class);
        com.astral.dao.mapper.SysNotifyRuleMapper ruleMapper = mock(com.astral.dao.mapper.SysNotifyRuleMapper.class);
        when(ruleMapper.selectCount(any())).thenReturn(0L);
        when(rules.lambdaQuery()).thenAnswer(call ->
                new com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper<>(ruleMapper));
        when(rules.save(any(SysNotifyRule.class))).thenAnswer(inv -> {
            SysNotifyRule saved = inv.getArgument(0);
            saved.setId(1L);
            return true;
        });
        SysMailTemplateMapper templates = mock(SysMailTemplateMapper.class);
        SysMailTemplate template = new SysMailTemplate();
        template.setId(1L);
        template.setScene(event);
        template.setChannel("INAPP");
        when(templates.selectById(1L)).thenReturn(template);
        SysNotifyRuleChannelMapper channelMapper = mock(SysNotifyRuleChannelMapper.class);
        SysNotifyRuleRecipientMapper recipientMapper = mock(SysNotifyRuleRecipientMapper.class);
        NotifyRuleController controller = new NotifyRuleController(rules, channelMapper, recipientMapper,
                templates, mock(SysSmsTemplateMapper.class), mock(NotifyPublisher.class));

        NotifyRuleSaveDto dto = new NotifyRuleSaveDto();
        dto.setRuleName("reply");
        dto.setEventCode(event);
        dto.setEnabled(1);
        NotifyRuleSaveDto.ChannelDto channel = new NotifyRuleSaveDto.ChannelDto();
        channel.setChannel("INAPP");
        channel.setTemplateId(1L);
        channel.setPlatform("app-windows");
        NotifyRuleSaveDto.RecipientDto recipient = new NotifyRuleSaveDto.RecipientDto();
        // 用户回复事件不声明 userId，固定值兜底
        recipient.setType(NotifyEventRegistry.FEEDBACK_USER_REPLIED.equals(event) ? "FIXED" : "PAYLOAD_FIELD");
        recipient.setValue(NotifyEventRegistry.FEEDBACK_USER_REPLIED.equals(event) ? "7" : "userId");
        channel.setRecipients(List.of(recipient));
        dto.setChannels(List.of(channel));

        controller.create(dto);

        ArgumentCaptor<SysNotifyRuleChannel> captor = ArgumentCaptor.forClass(SysNotifyRuleChannel.class);
        verify(channelMapper).insert(captor.capture());
        assertEquals("all", captor.getValue().getPlatform(), "反馈双方回复类事件站内信必须跨平台可见");
        assertEquals("INAPP", captor.getValue().getChannel());
        verify(recipientMapper).insert(any(com.astral.dao.entity.SysNotifyRuleRecipient.class));
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
        // 渠道可配：事件声明的是「支持」渠道，具体投递由规则决定
        assertEquals(java.util.List.of("EMAIL", "SMS", "INAPP"), event.channels());
        assertEquals(java.util.Set.of("userId", "addedRoles", "removedRoles", "changeSummary"),
                java.util.Set.copyOf(event.payloadFieldNames()));
    }

    @Test
    void passwordResetEventRequiresEmailChannel() {
        NotifyEventDef event = NotifyEventRegistry.find(NotifyEventRegistry.QT_PASSWORD_RESET_CODE).orElseThrow();
        assertEquals(java.util.List.of(NotifyChannel.EMAIL), event.requiredChannels());
        assertTrue(event.payloadFieldNames().contains("email"));
        assertTrue(event.hasRequiredChannel());
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
