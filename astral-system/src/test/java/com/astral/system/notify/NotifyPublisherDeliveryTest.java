package com.astral.system.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.dao.entity.SysMailTemplate;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.entity.SysNotifyRule;
import com.astral.dao.entity.SysNotifyRuleChannel;
import com.astral.dao.entity.SysNotifyRuleRecipient;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.SysMailTemplateMapper;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.dao.mapper.SysNotifyRuleChannelMapper;
import com.astral.dao.mapper.SysNotifyRuleMapper;
import com.astral.dao.mapper.SysNotifyRuleRecipientMapper;
import com.astral.dao.mapper.SysSmsTemplateMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.system.mail.MailService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Public publish results include real INAPP delivery; substitutes stop at database/Redis boundaries. */
class NotifyPublisherDeliveryTest {
    @Test
    void failedNoticeInsertIsReportedAsFailedDelivery() {
        Fixture f = new Fixture();
        when(f.notices.insert(any(SysNotice.class)))
                .thenThrow(new DataAccessResourceFailureException("fixture database unavailable"));

        var results = f.publish();

        assertFalse(results.getFirst().ok());
        assertTrue(results.getFirst().error().contains("fixture database unavailable"));
    }

    @Test
    @EnabledIfSystemProperty(named = "audit.redis.port", matches = "[0-9]+")
    void concurrentPublishCannotSpendTheSameDailyAllowance() throws Exception {
        try (RedisFixture backend = new RedisFixture()) {
            Fixture f = new Fixture(backend.template);
            f.channel.setDailyLimit(1);
            CountDownLatch sending = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicInteger deliveries = new AtomicInteger();
            when(f.notices.insert(any(SysNotice.class))).thenAnswer(call -> {
                if (deliveries.incrementAndGet() == 1) {
                    sending.countDown();
                    assertTrue(finish.await(15, TimeUnit.SECONDS));
                }
                return 1;
            });
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(f::publish);
                try {
                    assertTrue(sending.await(10, TimeUnit.SECONDS));
                    assertFalse(f.publish().getFirst().ok(), "in-flight delivery must already consume its allowance");
                } finally {
                    finish.countDown();
                }
                assertTrue(first.get(10, TimeUnit.SECONDS).getFirst().ok());
            }
            assertEquals(1, deliveries.get());
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "audit.redis.port", matches = "[0-9]+")
    void failedDeliveryReturnsAllowanceAndSuccessfulDeliveryConsumesIt() {
        try (RedisFixture backend = new RedisFixture()) {
            Fixture f = new Fixture(backend.template);
            f.channel.setDailyLimit(1);
            when(f.notices.insert(any(SysNotice.class)))
                    .thenThrow(new DataAccessResourceFailureException("temporary fixture failure"))
                    .thenReturn(1);

            assertFalse(f.publish().getFirst().ok());
            assertTrue(f.publish().getFirst().ok(), "failed delivery must return its permit");
            assertFalse(f.publish().getFirst().ok(), "successful delivery keeps its permit");
            assertTrue(
                    f.publisher
                            .publish(NotifyEventRegistry.SYSTEM_ALERT, Map.of(), List.of("42"))
                            .getFirst()
                            .ok(),
                    "recipient quota must not spend another recipient's allowance");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "audit.redis.port", matches = "[0-9]+")
    void globalAllowanceIsSharedAcrossRecipients() {
        try (RedisFixture backend = new RedisFixture()) {
            Fixture f = new Fixture(backend.template);
            f.channel.setDailyLimit(1);
            f.channel.setDailyLimitScope("GLOBAL");
            assertTrue(f.publish().getFirst().ok());
            assertFalse(f.publisher
                    .publish(NotifyEventRegistry.SYSTEM_ALERT, Map.of(), List.of("42"))
                    .getFirst()
                    .ok());
        }
    }

    @Test
    void quotaUnavailableFailsClosedAndDoesNotReportSuccessfulDelivery() {
        Fixture f = new Fixture();
        f.channel.setDailyLimit(1);
        assertFalse(f.publish().getFirst().ok());
    }

    @Test
    void channelLookupFailureDoesNotAbortLaterRules() {
        Fixture f = new Fixture();
        when(f.rules.selectList(any())).thenReturn(List.of(f.rule, f.rule));
        when(f.channels.selectList(any()))
                .thenThrow(new IllegalStateException("fixture query failure"))
                .thenReturn(List.of(f.channel));
        var results = f.publish();
        assertEquals(2, results.size());
        assertFalse(results.getFirst().ok());
        assertTrue(results.getLast().ok());
    }

    @Test
    void recipientLookupFailureDoesNotAbortLaterChannels() {
        Fixture f = new Fixture();
        when(f.channels.selectList(any())).thenReturn(List.of(f.channel, f.channel));
        when(f.recipients.selectList(any()))
                .thenThrow(new IllegalStateException("fixture recipient failure"))
                .thenReturn(List.of());
        var results = f.publish();
        assertEquals(2, results.size());
        assertFalse(results.getFirst().ok());
        assertTrue(results.getLast().ok());
    }

    @Test
    void asyncRoleLookupFailureStillDeliversOtherChannels() throws Exception {
        Fixture f = new Fixture();
        when(f.channels.selectList(any())).thenReturn(List.of(f.channel, f.channel));
        var role = new SysNotifyRuleRecipient();
        role.setRecipientType("ROLE");
        role.setRecipientValue("FIXTURE");
        var fixed = new SysNotifyRuleRecipient();
        fixed.setRecipientType("FIXED");
        fixed.setRecipientValue("41");
        when(f.recipients.selectList(any())).thenReturn(List.of(role), List.of(fixed));
        when(f.roles.selectOne(any())).thenThrow(new IllegalStateException("fixture role failure"));
        CountDownLatch sent = new CountDownLatch(1);
        when(f.notices.insert(any(SysNotice.class))).thenAnswer(call -> {
            sent.countDown();
            return 1;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var proxy = new org.springframework.aop.framework.ProxyFactory(f.publisher);
            proxy.addAdvisor(new org.springframework.scheduling.annotation.AsyncAnnotationAdvisor(executor, null));
            ((NotifyPublisher) proxy.getProxy()).publishAsync(NotifyEventRegistry.SYSTEM_ALERT, Map.of());
            assertTrue(sent.await(3, TimeUnit.SECONDS), "later channel must be delivered by the actual async proxy");
        }
    }

    private static final class RedisFixture implements AutoCloseable {
        final LettuceConnectionFactory connections =
                new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getProperty("audit.redis.port")));
        final StringRedisTemplate template;

        RedisFixture() {
            connections.afterPropertiesSet();
            connections.start();
            template = new StringRedisTemplate(connections);
        }

        @Override
        public void close() {
            connections.destroy();
        }
    }

    private static final class Fixture {
        final SysNoticeMapper notices = mock(SysNoticeMapper.class);
        final StringRedisTemplate redis;
        final SysNotifyRuleChannel channel = new SysNotifyRuleChannel();
        final SysNotifyRule rule = new SysNotifyRule();
        final SysNotifyRuleMapper rules = mock(SysNotifyRuleMapper.class);
        final SysNotifyRuleChannelMapper channels = mock(SysNotifyRuleChannelMapper.class);
        final SysNotifyRuleRecipientMapper recipients = mock(SysNotifyRuleRecipientMapper.class);
        final RoleMapper roles = mock(RoleMapper.class);
        final NotifyPublisher publisher;

        Fixture() {
            this(mock(StringRedisTemplate.class));
        }

        Fixture(StringRedisTemplate redis) {
            this.redis = redis;
            rule.setId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
            rule.setRuleName("Fixture rule");
            rule.setEventCode(NotifyEventRegistry.SYSTEM_ALERT);
            when(rules.selectList(any())).thenReturn(List.of(rule));
            SysNotifyRuleServiceImpl ruleService = new SysNotifyRuleServiceImpl(null, null, null, null);
            ReflectionTestUtils.setField(ruleService, "baseMapper", rules);
            channel.setId(2L);
            channel.setChannel(NotifyChannel.INAPP);
            channel.setTemplateId(3L);
            channel.setDailyLimit(0);
            when(channels.selectList(any())).thenReturn(List.of(channel));
            when(recipients.selectList(any())).thenReturn(List.of());
            SysMailTemplate template = new SysMailTemplate();
            template.setId(3L);
            template.setChannel(NotifyChannel.INAPP);
            template.setScene(NotifyEventRegistry.SYSTEM_ALERT);
            template.setSubject("${title}");
            template.setContent("${content}");
            SysMailTemplateMapper templates = mock(SysMailTemplateMapper.class);
            when(templates.selectById(3L)).thenReturn(template);
            when(notices.insert(any(SysNotice.class))).thenReturn(1);
            publisher = new NotifyPublisher(
                    ruleService,
                    channels,
                    recipients,
                    templates,
                    mock(SysSmsTemplateMapper.class),
                    roles,
                    mock(UserRoleMapper.class),
                    mock(UserMapper.class),
                    mock(MailService.class),
                    mock(SmsService.class),
                    new InappServiceImpl(notices, templates),
                    redis);
        }

        List<NotifyPublisher.PublishResult> publish() {
            return publisher.publish(
                    NotifyEventRegistry.SYSTEM_ALERT, Map.of("title", "Test", "content", "Test"), List.of("41"));
        }
    }
}
