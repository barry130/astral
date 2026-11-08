package com.astral.cms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.astral.cms.common.CmsException;
import com.astral.cms.dto.CommentSubmitDto;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.mapper.CmsCommentMapper;
import com.astral.cms.mapper.CmsPostMapper;
import com.astral.cms.mapper.CmsSettingMapper;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CmsCommentThrottleTest {
    @Test
    void capacityRejectsNewIpsWithoutEvictingExistingLimits() {
        var service = service(new CmsCommentService());
        for (int i = 0; i < 10_000; i++) service.submit(dto(), "fixture-" + i, "fixture");
        for (int i = 0; i < 100; i++)
            assertThrows(CmsException.class, () -> service.submit(dto(), "overflow", "fixture"));
        for (int i = 0; i < 4; i++) service.submit(dto(), "fixture-0", "fixture");
        assertThrows(CmsException.class, () -> service.submit(dto(), "fixture-0", "fixture"));
    }

    @Test
    void windowExpiryReleasesCapacityAndConcurrentSameIpNeverExceedsFive() throws Exception {
        AtomicLong nanos = new AtomicLong();
        var service = service(new CmsCommentService(nanos::get));
        for (int i = 0; i < 10_000; i++) service.submit(dto(), "fixture-" + i, "fixture");
        nanos.set(TimeUnit.SECONDS.toNanos(600));
        AtomicInteger accepted = new AtomicInteger();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 40; i++)
                pending.add(executor.submit(() -> {
                    try {
                        start.await();
                        service.submit(dto(), "new-ip", "fixture");
                        accepted.incrementAndGet();
                    } catch (CmsException expected) {
                        // Rejected callers consume no additional slot.
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }));
            start.countDown();
            for (var future : pending) future.get(5, TimeUnit.SECONDS);
        }
        assertEquals(5, accepted.get());
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(600));
        service.submit(dto(), "new-ip", "fixture");
    }

    private CmsCommentService service(CmsCommentService service) {
        var post = new CmsPost();
        post.setId(1L);
        post.setStatus("published");
        var posts = mock(CmsPostMapper.class);
        when(posts.selectById(1L)).thenReturn(post);
        ReflectionTestUtils.setField(service, "postMapper", posts);
        ReflectionTestUtils.setField(service, "commentMapper", mock(CmsCommentMapper.class));
        var settings = new CmsSettingService();
        var settingsMapper = mock(CmsSettingMapper.class);
        when(settingsMapper.selectList(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(settings, "settingMapper", settingsMapper);
        ReflectionTestUtils.setField(service, "settingService", settings);
        ReflectionTestUtils.setField(service, "throttleWindowSeconds", 600L);
        ReflectionTestUtils.setField(service, "throttleMax", 5);
        return service;
    }

    private CommentSubmitDto dto() {
        var dto = new CommentSubmitDto();
        dto.setTargetType("post");
        dto.setTargetId(1L);
        dto.setContent("Content");
        dto.setNickname("Reader");
        return dto;
    }
}
