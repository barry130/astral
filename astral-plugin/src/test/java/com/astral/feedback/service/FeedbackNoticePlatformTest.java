package com.astral.feedback.service;

import com.astral.dao.entity.SysNotice;
import com.astral.dao.mapper.SysNoticeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeedbackNoticePlatformTest {
    @ParameterizedTest
    @ValueSource(strings = {"feedbackAdminReplied", "feedbackUserReplied"})
    void editingReplyRetainsAllPlatformsUsingStoredScene(String scene) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "notice-test"), SysNotice.class);
        SysNoticeMapper mapper = mock(SysNoticeMapper.class);
        FeedbackNoticeService service = new FeedbackNoticeService();
        ReflectionTestUtils.setField(service, "sysNoticeMapper", mapper);
        SysNotice existing = new SysNotice();
        existing.setId(5L);
        existing.setScene(scene);
        when(mapper.selectById(5L)).thenReturn(existing);
        SysNotice edit = new SysNotice();
        edit.setChannel("app-android");
        edit.setScene("announce");
        service.update(5L, edit);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<SysNotice>> wrapper = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), wrapper.capture());
        assertTrue(wrapper.getValue().getSqlSet().contains("channel="));
        assertTrue(((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysNotice>) wrapper.getValue())
                .getParamNameValuePairs().containsValue("all"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"feedbackAdminReplied", "feedbackUserReplied"})
    void creatingReplyUsesAllPlatforms(String scene) {
        SysNoticeMapper mapper = mock(SysNoticeMapper.class);
        FeedbackNoticeService service = new FeedbackNoticeService();
        ReflectionTestUtils.setField(service, "sysNoticeMapper", mapper);
        SysNotice notice = new SysNotice();
        notice.setScene(scene);
        notice.setChannel("app-windows");
        assertEquals("all", service.create(notice).getChannel());
        verify(mapper).insert(notice);
    }
}
