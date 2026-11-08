package com.astral.feedback.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.astral.feedback.entity.Feedback;
import com.astral.feedback.mapper.FeedbackMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 覆盖反馈公开视图脱敏（D-FB-01）：公开列表/他人可见详情不能暴露 IP、联系方式、设备。
 */
class FeedbackServiceRedactionTest {

    private FeedbackService service;
    private FeedbackMapper feedbackMapper;

    @BeforeEach
    void setUp() {
        service = new FeedbackService();
        feedbackMapper = mock(FeedbackMapper.class);
        ReflectionTestUtils.setField(service, "feedbackMapper", feedbackMapper);
    }

    @Test
    void publicPageRedactsPrivateFields() {
        Feedback feedback = new Feedback();
        feedback.setId(1L);
        feedback.setStatus(Feedback.STATUS_PUBLISHED);
        feedback.setIsPublic(true);
        feedback.setIp("10.0.0.1");
        feedback.setContact("secret@example.com");
        feedback.setDevice("Pixel");
        Page<Feedback> page = new Page<>(1, 10);
        page.setRecords(new ArrayList<>(List.of(feedback)));
        when(feedbackMapper.selectPage(any(), any())).thenReturn(page);

        Page<Feedback> result = service.publicPage(1, 10);

        Feedback out = result.getRecords().get(0);
        assertNull(out.getIp());
        assertNull(out.getContact());
        assertNull(out.getDevice());
    }

    @Test
    void detailRedactsForOtherViewerButKeepsForOwner() {
        Feedback feedback = new Feedback();
        feedback.setId(2L);
        feedback.setUserId(20L);
        feedback.setStatus(Feedback.STATUS_PUBLISHED);
        feedback.setIsPublic(true);
        feedback.setIp("10.0.0.2");
        feedback.setContact("owner-contact");
        feedback.setDevice("DeviceX");
        when(feedbackMapper.selectById(2L)).thenReturn(feedback);

        Feedback other = service.detail(99L, 2L, false);
        assertNull(other.getIp());
        assertNull(other.getContact());
        assertNull(other.getDevice());

        Feedback own = new Feedback();
        own.setId(3L);
        own.setUserId(30L);
        own.setStatus(Feedback.STATUS_PUBLISHED);
        own.setIsPublic(true);
        own.setIp("10.0.0.3");
        own.setContact("mine");
        own.setDevice("MyDevice");
        when(feedbackMapper.selectById(3L)).thenReturn(own);

        Feedback mine = service.detail(30L, 3L, false);
        assertEquals("10.0.0.3", mine.getIp());
        assertEquals("mine", mine.getContact());
        assertEquals("MyDevice", mine.getDevice());
    }
}
