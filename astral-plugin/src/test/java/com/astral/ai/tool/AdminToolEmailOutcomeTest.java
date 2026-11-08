package com.astral.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.astral.ai.common.AiJson;
import com.astral.ai.tool.AiToolTypes.AiToolContext;
import com.astral.ai.tool.AiToolTypes.AiToolResult;
import com.astral.dao.entity.SysMailAccount;
import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysNotifyRuleChannel;
import com.astral.dao.entity.SysSmsProvider;
import com.astral.dao.entity.SysSmsTemplate;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.SysMailAccountMapper;
import com.astral.dao.mapper.SysMailLogMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsLogMapper;
import com.astral.dao.mapper.SysSmsProviderMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.mail.MailServiceImpl;
import com.astral.system.notify.InappServiceImpl;
import com.astral.system.notify.NotifyPublisher;
import com.astral.system.notify.SmsServiceImpl;
import com.astral.system.notify.SysNotifyRuleServiceImpl;
import com.astral.system.notify.sms.SmsProvider;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

/** Public tool -> real publisher/mail service; only database and SMTP boundaries are replaced. */
class AdminToolEmailOutcomeTest {
    @Test
    void mixedChannelRuleSendsOnlyTheApprovedEmail() {
        var fixture = new Fixture();
        fixture.useChannels("EMAIL", "INAPP", "SMS");
        var submitted = new AtomicInteger();
        try (var smtp = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
            doAnswer(call -> {
                        submitted.incrementAndGet();
                        return null;
                    })
                    .when(sender)
                    .send(any(MimeMessage.class));
        })) {
            var result = fixture.execute();
            assertEquals(AiToolResult.SUCCEEDED, result.status());
            assertEquals(1, submitted.get());
            assertEquals(1, result.payload().path("submittedCount").asInt());
            assertEquals(0, fixture.smsAttempts.get(), "email approval must not send SMS");
            assertEquals(0, fixture.notices.get(), "email approval must not create an in-app notice");
        }
    }

    @Test
    void nonEmailRulesAreRejectedWithoutSendingAnything() {
        var fixture = new Fixture();
        fixture.useChannels("INAPP", "SMS");
        try (var smtp = mockConstruction(JavaMailSenderImpl.class)) {
            assertEquals(AiToolResult.REJECTED, fixture.execute().status());
            assertEquals(0, fixture.smsAttempts.get());
            assertEquals(0, fixture.notices.get());
            assertEquals(0, smtp.constructed().size());
        }
    }

    @Test
    void noEnabledRuleDoesNotClaimMailWasSubmitted() {
        var fixture = new Fixture();
        when(fixture.rules.selectList(any())).thenReturn(List.of());
        assertEquals(AiToolResult.REJECTED, fixture.execute().status());
    }

    @Test
    void smtpFailureIsNotReportedAsSuccessOrReflectedIntoTheToolResult() {
        var fixture = new Fixture();
        try (var smtp = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
            doThrow(new MailSendException("fixture-sensitive-upstream-detail"))
                    .when(sender)
                    .send(any(MimeMessage.class));
        })) {
            var result = fixture.execute();
            assertEquals(AiToolResult.UNKNOWN, result.status());
            assertFalse(result.summary().contains("fixture-sensitive-upstream-detail"));
        }
    }

    @Test
    void partialDeliveryRetainsConfirmedCountAndRequiresManualChecking() {
        var fixture = new Fixture();
        var first = new SysNotifyRuleChannel();
        first.setId(5L);
        first.setChannel("EMAIL");
        first.setTemplateId(3L);
        var second = new SysNotifyRuleChannel();
        second.setId(6L);
        second.setChannel("EMAIL");
        second.setTemplateId(3L);
        when(fixture.channels.selectList(any())).thenReturn(List.of(first, second));
        try (var smtp = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
            if (context.getCount() == 2) {
                doThrow(new MailSendException("fixture failure")).when(sender).send(any(MimeMessage.class));
            }
        })) {
            var result = fixture.execute();
            assertEquals(AiToolResult.UNKNOWN, result.status());
            assertEquals(1, result.payload().path("submittedCount").asInt());
            assertEquals(1, result.payload().path("failedCount").asInt());
        }
    }

    @Test
    void confirmedSmtpSubmissionIsReportedAsSuccess() {
        var fixture = new Fixture();
        try (var smtp = mockConstruction(JavaMailSenderImpl.class, (sender, context) -> {
            when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        })) {
            var result = fixture.execute();
            assertEquals(AiToolResult.SUCCEEDED, result.status());
            assertEquals(1, result.payload().path("submittedCount").asInt());
        }
    }

    private static final class Fixture {
        final SysNotifyRuleMapper rules = mock(SysNotifyRuleMapper.class);
        final SysNotifyRuleChannelMapper channels = mock(SysNotifyRuleChannelMapper.class);
        final AtomicInteger smsAttempts = new AtomicInteger();
        final AtomicInteger notices = new AtomicInteger();
        final AdminToolProvider provider;

        Fixture() {
            var rule = new SysNotifyRule();
            rule.setId(1L);
            rule.setRuleName("Fixture rule");
            rule.setEventCode("systemAlert");
            when(rules.selectList(any())).thenReturn(List.of(rule));
            var ruleService = new SysNotifyRuleServiceImpl();
            ReflectionTestUtils.setField(ruleService, "baseMapper", rules);
            var channel = new SysNotifyRuleChannel();
            channel.setId(2L);
            channel.setChannel("EMAIL");
            channel.setTemplateId(3L);
            channel.setDailyLimit(0);
            when(channels.selectList(any())).thenReturn(List.of(channel));
            var recipients = mock(SysNotifyRuleRecipientMapper.class);
            when(recipients.selectList(any())).thenReturn(List.of());
            var templates = mock(SysMailTemplateMapper.class);
            var template = new SysMailTemplate();
            template.setId(3L);
            template.setChannel("EMAIL");
            template.setScene("systemAlert");
            template.setSubject("Fixture");
            template.setContent("Fixture");
            when(templates.selectById(3L)).thenReturn(template);
            var inappTemplate = new SysMailTemplate();
            inappTemplate.setId(7L);
            inappTemplate.setChannel("INAPP");
            inappTemplate.setScene("systemAlert");
            inappTemplate.setSubject("Fixture");
            inappTemplate.setContent("Fixture");
            when(templates.selectById(7L)).thenReturn(inappTemplate);
            var noticeMapper = mock(SysNoticeMapper.class);
            when(noticeMapper.insert(any(SysNotice.class))).thenAnswer(call -> {
                notices.incrementAndGet();
                return 1;
            });
            var smsTemplates = mock(SysSmsTemplateMapper.class);
            var smsTemplate = new SysSmsTemplate();
            smsTemplate.setId(8L);
            smsTemplate.setEventCode("systemAlert");
            smsTemplate.setProviderTemplateCode("fixture");
            when(smsTemplates.selectById(8L)).thenReturn(smsTemplate);
            var smsProviders = mock(SysSmsProviderMapper.class);
            var smsProvider = new SysSmsProvider();
            smsProvider.setId(9L);
            smsProvider.setProviderType("FIXTURE");
            smsProvider.setEnabled(1);
            smsProvider.setWeight(1);
            when(smsProviders.selectList(any())).thenReturn(List.of(smsProvider));
            var sms = new SmsServiceImpl(
                    List.of(new SmsProvider() {
                        @Override
                        public String type() {
                            return "FIXTURE";
                        }

                        @Override
                        public void send(
                                SysSmsProvider provider,
                                String phone,
                                String signName,
                                String templateCode,
                                Map<String, String> params) {
                            smsAttempts.incrementAndGet();
                        }
                    }),
                    smsProviders,
                    smsTemplates,
                    mock(SysSmsLogMapper.class));
            var accounts = mock(SysMailAccountMapper.class);
            var account = new SysMailAccount();
            account.setId(4L);
            account.setFromAddr("fixture@example.test");
            account.setSmtpHost("smtp.example.test");
            account.setSmtpPort(25);
            when(accounts.selectList(any())).thenReturn(List.of(account));
            var mail = new MailServiceImpl();
            ReflectionTestUtils.setField(mail, "templateMapper", templates);
            ReflectionTestUtils.setField(mail, "accountMapper", accounts);
            ReflectionTestUtils.setField(mail, "logMapper", mock(SysMailLogMapper.class));
            var publisher = new NotifyPublisher(
                    ruleService,
                    channels,
                    recipients,
                    templates,
                    smsTemplates,
                    mock(RoleMapper.class),
                    mock(UserRoleMapper.class),
                    mock(UserMapper.class),
                    mail,
                    sms,
                    new InappServiceImpl(noticeMapper, templates),
                    mock(StringRedisTemplate.class));
            provider = new AdminToolProvider(
                    null, null, null, null, null, null, null, null, null, null, null, null, mail, publisher, null,
                    null);
        }

        void useChannels(String... codes) {
            var configured = new java.util.ArrayList<SysNotifyRuleChannel>();
            for (String code : codes) {
                var channel = new SysNotifyRuleChannel();
                channel.setId((long) configured.size() + 10);
                channel.setChannel(code);
                channel.setTemplateId("EMAIL".equals(code) ? 3L : "INAPP".equals(code) ? 7L : 8L);
                channel.setDailyLimit(0);
                configured.add(channel);
            }
            when(channels.selectList(any())).thenReturn(configured);
        }

        AiToolResult execute() {
            var arguments = AiJson.mapper()
                    .createObjectNode()
                    .put("toEmail", "receiver@example.test")
                    .put("sceneCode", "systemAlert");
            var context =
                    new AiToolContext(1L, 2L, 3L, 4L, "fixture", "ADMIN", "ASTRAL_FRONT", arguments, null, true, 3L);
            return provider.handlers().stream()
                    .filter(handler -> "send_email".equals(handler.definition().toolCode()))
                    .findFirst()
                    .orElseThrow()
                    .execute(context);
        }
    }
}
