package com.astral.system.notice;

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

/**
 * 反馈回复类通知的「全平台可见」约束在宿主通知服务里的落库口径。
 *
 * <p>为什么这两条要留在通知服务上：反馈双方的回复通知由 INAPP 事件投递产生，
 * 但管理员事后可能在后台编辑/新建时误改渠道；{@code scene} 是事件码，落库后即为
 * 权威归属，所以 create/update 都以「库里的 scene」为准把 channel 强制改写为 {@code all}
 * （管理端不按客户端平台过滤，回复双方在任意端都该看见）。</p>
 *
 * <p><b>迁移说明</b>：本类原为 {@code com.astral.feedback.service.FeedbackNoticePlatformTest}，
 * 随通知能力收归宿主改名为 {@link NoticeServiceImpl}，断言逻辑未改。</p>
 */
class NoticePlatformTest {

    @ParameterizedTest
    @ValueSource(strings = {"feedbackAdminReplied", "feedbackUserReplied"})
    void editingReplyRetainsAllPlatformsUsingStoredScene(String scene) {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "notice-test"), SysNotice.class);
        SysNoticeMapper mapper = mock(SysNoticeMapper.class);
        NoticeServiceImpl service = new NoticeServiceImpl();
        ReflectionTestUtils.setField(service, "sysNoticeMapper", mapper);
        SysNotice existing = new SysNotice();
        existing.setId(5L);
        existing.setScene(scene);
        when(mapper.selectById(5L)).thenReturn(existing);
        SysNotice edit = new SysNotice();
        edit.setChannel("app-android");
        // 编辑请求里改 scene 不算数：归属以库里的行为准
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
        NoticeServiceImpl service = new NoticeServiceImpl();
        ReflectionTestUtils.setField(service, "sysNoticeMapper", mapper);
        SysNotice notice = new SysNotice();
        notice.setScene(scene);
        notice.setChannel("app-windows");
        assertEquals("all", service.create(notice).getChannel());
        verify(mapper).insert(notice);
    }
}
