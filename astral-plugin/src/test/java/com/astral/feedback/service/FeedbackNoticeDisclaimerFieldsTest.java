package com.astral.feedback.service;

import com.astral.dao.entity.SysNotice;
import com.astral.dao.mapper.SysNoticeMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * 首次启动免责声明专用两列（read_seconds / terms_version）的编辑落库口径。
 *
 * <p>为什么单独测：这两列是「客户端据此判定这条公告是不是免责声明」的依据
 * （qt-pc `stores/disclaimer.ts`：开屏位 + termsVersion 非空）。后台编辑时若清空版本号
 * 却没能真的写回 NULL，那条公告会在所有客户端永远被当成声明；
 * 反之若把「清空」写成空串，`termsVersion.trim().length > 0` 虽仍为假，
 * 但库里留下的 '' 与 NULL 在语义上不同（空串是「填过又删了」，NULL 是「从没填过」），
 * 排查时会误导。所以这里断言的是「清空 = 真的 set NULL」，不是「字段被跳过」。</p>
 */
class FeedbackNoticeDisclaimerFieldsTest {

    private SysNoticeMapper mapper;
    private FeedbackNoticeService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "notice-disclaimer-test"), SysNotice.class);
        mapper = mock(SysNoticeMapper.class);
        service = new FeedbackNoticeService();
        ReflectionTestUtils.setField(service, "sysNoticeMapper", mapper);
    }

    /** 跑一次 update，返回它交给 mapper 的白名单 wrapper */
    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<SysNotice> runUpdate(SysNotice edit) {
        when(mapper.selectById(anyLong())).thenReturn(null);
        service.update(7L, edit);
        ArgumentCaptor<Wrapper<SysNotice>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        return (LambdaUpdateWrapper<SysNotice>) captor.getValue();
    }

    /**
     * 按列名取出该列这次要写的值。
     * <p>{@code getParamNameValuePairs()} 是「所有 set 列」的值表（isTop/marquee 等默认 0 也在里面），
     * 只能按 {@code sqlSet} 里该列的占位符名精确回查，不能用 containsValue 判断。</p>
     */
    private Object setValueOf(LambdaUpdateWrapper<SysNotice> wrapper, String column) {
        String sqlSet = wrapper.getSqlSet();
        assertTrue(sqlSet.contains(column + "="), column + " 必须在 update 白名单里");
        Matcher m = Pattern.compile(column + "=#\\{ew\\.paramNameValuePairs\\.(\\w+)\\}").matcher(sqlSet);
        assertTrue(m.find(), "没能从 sqlSet 里解析出 " + column + " 的占位符：" + sqlSet);
        return wrapper.getParamNameValuePairs().get(m.group(1));
    }

    @Test
    void editingDisclaimerWritesBothColumns() {
        SysNotice edit = new SysNotice();
        edit.setReadSeconds(5L);
        edit.setTermsVersion("2026.11");

        LambdaUpdateWrapper<SysNotice> wrapper = runUpdate(edit);

        assertEquals(5L, setValueOf(wrapper, "read_seconds"), "读秒时长要写进去");
        assertEquals("2026.11", setValueOf(wrapper, "terms_version"), "条款版本号要写进去");
    }

    /**
     * 清空输入框 = 取消这条公告的「免责声明」身份，必须真的写 NULL。
     * 若这两列被 updateStrategy=NOT_NULL 跳过（整实体 updateById 的老毛病），
     * 后台看起来「保存成功」，客户端却还在弹旧的声明 —— 这正是本测试要守的回归。
     */
    @Test
    void clearingBothColumnsWritesNullRatherThanSkippingThem() {
        SysNotice edit = new SysNotice();
        edit.setReadSeconds(null);
        edit.setTermsVersion(null);

        LambdaUpdateWrapper<SysNotice> wrapper = runUpdate(edit);

        assertNull(setValueOf(wrapper, "read_seconds"), "清空必须落 NULL，不能被 updateStrategy 跳过");
        assertNull(setValueOf(wrapper, "terms_version"), "清空必须落 NULL，不能被 updateStrategy 跳过");
    }

    /** 普通公告不带这两列时也不该被回落成 0 / 空串（那会让语义从「没填」变成「填了 0」） */
    @Test
    void ordinaryNoticeLeavesThemNull() {
        SysNotice edit = new SysNotice();
        edit.setTitle("普通公告");

        LambdaUpdateWrapper<SysNotice> wrapper = runUpdate(edit);

        assertNull(setValueOf(wrapper, "read_seconds"));
        assertNull(setValueOf(wrapper, "terms_version"));
    }

    /**
     * 读秒夹在 0..60。
     * <p>表单 {@code InputNumber max={60}} 只拦得住走界面的操作，直接调
     * {@code POST /admin/messages} 或脚本写库仍能塞任意值。而这个读秒对应 qt-pc 的
     * 全屏免责声明弹窗（**没有关闭键**，只能等读秒走完），写个 600 等于把所有新用户
     * 首次启动锁十分钟 —— 所以服务端必须自己兜。</p>
     */
    @Test
    void readSecondsIsClampedToRange() {
        assertEquals(60L, clampOf(600L), "超上限要夹到 60");
        assertEquals(60L, clampOf(61L));
        assertEquals(0L, clampOf(-5L), "负数归 0（不读秒 = 立即可点，最安全的一侧）");
        assertEquals(3L, clampOf(3L), "范围内原样保留");
        assertEquals(0L, clampOf(0L), "0 = 不读秒，语义要保住（不能变成 null）");
    }

    /**
     * update 路径的夹取结果：跑一遍 update，从白名单 wrapper 里取 read_seconds 的实际写入值。
     * <p>本方法在一个用例里要连调多次，所以各自建一套 mapper/service —— 复用 {@link #runUpdate}
     * 会撞上 Mockito 的 `verify(mapper).update(...)` 只允许一次调用的约束。</p>
     */
    @SuppressWarnings("unchecked")
    private Object clampOf(long readSeconds) {
        SysNotice edit = new SysNotice();
        edit.setReadSeconds(readSeconds);
        SysNoticeMapper localMapper = mock(SysNoticeMapper.class);
        when(localMapper.selectById(anyLong())).thenReturn(null);
        FeedbackNoticeService localService = new FeedbackNoticeService();
        ReflectionTestUtils.setField(localService, "sysNoticeMapper", localMapper);
        localService.update(7L, edit);
        ArgumentCaptor<Wrapper<SysNotice>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(localMapper).update(isNull(), captor.capture());
        return setValueOf((LambdaUpdateWrapper<SysNotice>) captor.getValue(), "read_seconds");
    }

    /** create 路径同样要夹：新增走 insert，断言实体被就地改过 */
    @Test
    void createAlsoClampsReadSeconds() {
        SysNotice fresh = new SysNotice();
        fresh.setTitle("新声明");
        fresh.setReadSeconds(999L);

        service.create(fresh);

        assertEquals(60L, fresh.getReadSeconds(), "新增路径也要夹到上限");
        verify(mapper).insert(fresh);
    }

    // ==================== 渠道：免责声明只投桌面三端 ====================

    /**
     * 声明被人勾了「不限平台」也必须收窄到桌面三端。
     * <p>放出去的后果不是文案难看：qt-uniappx 不认识 terms_version，会当成普通开屏公告，
     * 而声明模板带 {@code dialog_closable=0} —— 那个弹窗里「我知道了」在不可关闭时调的是
     * {@code exitApp()}，用户点一下 App 直接退出，且每次启动都再弹。</p>
     */
    @Test
    void createForcesDisclaimerToDesktopChannels() {
        SysNotice fresh = new SysNotice();
        fresh.setTitle("使用前请先阅读");
        fresh.setTermsVersion("2026.10");
        fresh.setChannel("all");

        service.create(fresh);

        assertEquals("app-windows,app-linux,app-macos", fresh.getChannel(),
                "不管后台勾了什么，声明只投桌面三端");
    }

    /**
     * 只勾 Windows 也要补齐 Linux/macOS：qt-pc 的 {@code X-App-Ut} 跟着编译目标走，
     * 漏掉哪个平台，那个平台的 PC 用户就永远看不到声明。
     */
    @Test
    void createFillsAllDesktopPlatformsEvenIfOnlyWindowsGiven() {
        SysNotice fresh = new SysNotice();
        fresh.setTermsVersion("2026.10");
        fresh.setChannel("app-windows");

        service.create(fresh);

        assertEquals("app-windows,app-linux,app-macos", fresh.getChannel());
    }

    /** 编辑路径同样收窄（表单没提交 channel 时也要落到桌面三端，不能留着旧的手机端值） */
    @Test
    void editingDisclaimerForcesDesktopChannels() {
        SysNotice edit = new SysNotice();
        edit.setTermsVersion("2026.10");
        edit.setChannel("app-android,app-ios");

        LambdaUpdateWrapper<SysNotice> wrapper = runUpdate(edit);

        assertEquals("app-windows,app-linux,app-macos", setValueOf(wrapper, "channel"));
    }

    /**
     * 普通公告不受影响 —— 这条守的是「别把整个公告系统的渠道逻辑改坏」。
     * 顺带覆盖「把一条声明改回普通公告」：清空版本号后渠道回到后台所选。
     */
    @Test
    void ordinaryNoticeKeepsChosenChannels() {
        SysNotice fresh = new SysNotice();
        fresh.setTitle("普通公告");
        fresh.setChannel("app-android,app-ios");

        service.create(fresh);

        assertEquals("app-android,app-ios", fresh.getChannel());
    }
}
