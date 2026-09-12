package com.astral.feedback.service;

import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.feedback.entity.SysNotice;
import com.astral.feedback.mapper.SysNoticeMapper;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 统一通知服务
 * <p>负责 App 端通知的查询过滤链、管理端公告 CRUD，以及反馈/需求事件的通知触发。</p>
 */
@Slf4j
@Service
public class FeedbackNoticeService {

    /** 展示位掩码：消息中心 */
    public static final long DISPLAY_MESSAGE_CENTER = 4L;
    /** 展示位掩码：开屏 */
    public static final long DISPLAY_SPLASH = 1L;
    /** 展示位掩码：通告栏 */
    public static final long DISPLAY_NOTICE_BAR = 2L;

    /** 保留期配置键 */
    private static final String KEY_RETENTION_DAYS = "notice.feedback.retention_days";
    /** 默认保留天数（0 或空 = 不限） */
    private static final int DEFAULT_RETENTION_DAYS = 7;

    /** 通知管理员固定账号 */
    private static final String ADMIN_USERNAME = "admin";

    @Resource
    private SysNoticeMapper sysNoticeMapper;

    @Resource
    private SysConfigService sysConfigService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private FeedbackSequenceService feedbackSequenceService;

    /**
     * 当前生效通知列表（App 端，公开，三展示位共用）
     * <p>等价于 {@link #listForChannel(String, String, boolean, Long)} 传入渠道 app。</p>
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForApp(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(SysNotice.CHANNEL_APP, appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（PC 端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForPc(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(SysNotice.CHANNEL_PC, appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（Web 端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForWeb(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(SysNotice.CHANNEL_WEB, appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（指定端，公开）
     * <p>过滤链（§3.3）：is_show=1 → channel∈(指定端, all) → 时间窗 → 版本码区间 → audience → 广播或点对点 → 保留期。</p>
     * <p>支持的端：app | pc | web，未知值按 app 处理（兼容已发布客户端不传该参数）。</p>
     *
     * @param channel    端标识：app | pc | web
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForChannel(String channel, String appVersion, boolean loggedIn, Long userId) {
        LocalDateTime retentionFrom = retentionFrom();
        LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<>();
        qw.eq(SysNotice::getIsShow, 1L);
        qw.in(SysNotice::getChannel, Arrays.asList(normalizeChannel(channel), SysNotice.CHANNEL_ALL));
        // 保留期：announce 不受限；其余类型仅最近 N 天（N=0/空=不限）
        if (retentionFrom != null) {
            qw.and(w -> w.eq(SysNotice::getNoticeType, SysNotice.TYPE_ANNOUNCE)
                    .or().ge(SysNotice::getCreateTime, retentionFrom));
        }
        qw.orderByDesc(SysNotice::getIsTop).orderByDesc(SysNotice::getCreateTime);
        List<SysNotice> all = sysNoticeMapper.selectList(qw);

        return all.stream()
                .filter(n -> inEffectiveWindow(n, LocalDateTime.now()))
                .filter(n -> inVersionRange(n, appVersion))
                .filter(n -> audienceMatches(n.getAudience(), loggedIn))
                .filter(n -> n.getUserId() == null || (userId != null && userId.equals(n.getUserId())))
                .collect(Collectors.toList());
    }

    /**
     * 消息中心列表（App 端）
     * <p>在 listForApp 基础上追加 display 含消息中心(4)，返回带 noticeType 供前端区分标签。</p>
     * <p>已读状态由前端缓存判断，后端不返回 read 字段。</p>
     */
    public List<SysNotice> listMessageCenter(Long userId) {
        return listMessageCenter(userId, SysNotice.CHANNEL_APP);
    }

    /**
     * 消息中心列表（指定端：app | pc | web）
     * <p>在 listForChannel 基础上追加 display 含消息中心(4)。</p>
     */
    public List<SysNotice> listMessageCenter(Long userId, String channel) {
        return listForChannel(channel, null, userId != null, userId).stream()
                .filter(n -> hasDisplay(n.getDisplay(), DISPLAY_MESSAGE_CENTER))
                .collect(Collectors.toList());
    }

    /**
     * 未读数（候选总数，App 端）
     * <p>返回该用户可见消息中心条目总数；已读判定在前端缓存，清缓存=全部未读（既定决策 D6）。</p>
     */
    public long countUnread(Long userId) {
        return countUnread(userId, SysNotice.CHANNEL_APP);
    }

    /**
     * 未读数（候选总数，指定端：app | pc | web）
     */
    public long countUnread(Long userId, String channel) {
        return listMessageCenter(userId, channel).size();
    }

    /** 已读回执（本期仅记日志，不落表） */
    public void readAck(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        log.info("[FeedbackPlugin] 用户 {} 已读回执 {} 条: {}", userId, ids.size(), ids);
    }

    // ==================== 管理端 ====================

    /** 通知分页（channel/notice_type/关键词/时间筛选） */
    public Page<SysNotice> page(int pageNum, int pageSize, String channel, String noticeType,
                                String keyword, String startDate, String endDate) {
        Page<SysNotice> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<>();
        if (channel != null && !channel.isBlank()) {
            qw.eq(SysNotice::getChannel, channel);
        }
        if (noticeType != null && !noticeType.isBlank()) {
            qw.eq(SysNotice::getNoticeType, noticeType);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(SysNotice::getTitle, keyword).or().like(SysNotice::getContent, keyword));
        }
        if (startDate != null && !startDate.isBlank()) {
            qw.ge(SysNotice::getCreateTime, startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            qw.le(SysNotice::getCreateTime, endDate + " 23:59:59");
        }
        qw.orderByDesc(SysNotice::getCreateTime);
        return sysNoticeMapper.selectPage(page, qw);
    }

    /** 新增通知（公告/定向） */
    public SysNotice create(SysNotice notice) {
        if (notice.getChannel() == null || notice.getChannel().isBlank()) {
            notice.setChannel(SysNotice.CHANNEL_APP);
        }
        if (notice.getNoticeType() == null || notice.getNoticeType().isBlank()) {
            notice.setNoticeType(SysNotice.TYPE_ANNOUNCE);
        }
        if (notice.getDisplay() == null) {
            notice.setDisplay(DISPLAY_MESSAGE_CENTER);
        }
        if (notice.getIsShow() == null) notice.setIsShow(1L);
        if (notice.getIsTop() == null) notice.setIsTop(0L);
        if (notice.getDialogClosable() == null) notice.setDialogClosable(1L);
        if (notice.getFirstLoginOnly() == null) notice.setFirstLoginOnly(0L);
        if (notice.getMarquee() == null) notice.setMarquee(0L);
        if (notice.getAudience() == null || notice.getAudience().isBlank()) {
            notice.setAudience("ALL");
        }
        if (notice.getId() == null) {
            notice.setId(feedbackSequenceService.nextId("sys_notice"));
        }
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        sysNoticeMapper.insert(notice);
        return notice;
    }

    /** 更新通知 */
    public void update(Long id, SysNotice notice) {
        notice.setId(id);
        notice.setUpdateTime(LocalDateTime.now());
        sysNoticeMapper.updateById(notice);
    }

    /** 删除通知（物理删） */
    public void delete(Long id) {
        sysNoticeMapper.deleteById(id);
    }

    // ==================== 通知触发（异步） ====================

    /**
     * 反馈状态变更通知提交人
     *
     * @param feedbackId  反馈ID
     * @param userId      提交人
     * @param title       反馈标题
     * @param noticeType  通知类型（feedback/request）
     * @param statusName  状态名（中文）
     */
    @Async
    public void notifyStatusChange(Long feedbackId, Long userId, String title, String noticeType, String statusName) {
        try {
            SysNotice n = new SysNotice();
            n.setChannel(SysNotice.CHANNEL_APP);
            n.setNoticeType(noticeType);
            n.setUserId(userId);
            n.setFeedbackId(feedbackId);
            n.setDisplay(DISPLAY_MESSAGE_CENTER);
            n.setTitle("您的反馈「" + title + "」" + statusName);
            n.setContent("您的反馈「" + title + "」状态已更新为「" + statusName + "」。");
            n.setAudience("ALL");
            sysNoticeMapper.insert(fillDefault(n));
            log.info("[FeedbackPlugin] 状态变更通知已生成 feedbackId={}, userId={}", feedbackId, userId);
        } catch (Exception e) {
            log.warn("[FeedbackPlugin] 状态变更通知生成失败: {}", e.getMessage());
        }
    }

    /** 反馈已公开发布通知提交人 */
    @Async
    public void notifyPublished(Long feedbackId, Long userId, String title, String noticeType) {
        try {
            SysNotice n = new SysNotice();
            n.setChannel(SysNotice.CHANNEL_APP);
            n.setNoticeType(noticeType);
            n.setUserId(userId);
            n.setFeedbackId(feedbackId);
            n.setDisplay(DISPLAY_MESSAGE_CENTER);
            n.setTitle("您的反馈「" + title + "」已公开发布");
            n.setContent("您的反馈已通过审核并公开发布，其他用户可以在「公开」列表中看到。");
            n.setAudience("ALL");
            sysNoticeMapper.insert(fillDefault(n));
            log.info("[FeedbackPlugin] 公开发布通知已生成 feedbackId={}, userId={}", feedbackId, userId);
        } catch (Exception e) {
            log.warn("[FeedbackPlugin] 公开发布通知生成失败: {}", e.getMessage());
        }
    }

    /** 管理员回复通知提交人 */
    @Async
    public void notifyAdminReply(Long feedbackId, Long userId, String title, String noticeType) {
        try {
            SysNotice n = new SysNotice();
            n.setChannel(SysNotice.CHANNEL_APP);
            n.setNoticeType(noticeType);
            n.setUserId(userId);
            n.setFeedbackId(feedbackId);
            n.setDisplay(DISPLAY_MESSAGE_CENTER);
            n.setTitle("您的反馈「" + title + "」有新回复");
            n.setContent("管理员回复了您的反馈，点击查看详情。");
            n.setAudience("ALL");
            sysNoticeMapper.insert(fillDefault(n));
            log.info("[FeedbackPlugin] 管理员回复通知已生成 feedbackId={}, userId={}", feedbackId, userId);
        } catch (Exception e) {
            log.warn("[FeedbackPlugin] 管理员回复通知生成失败: {}", e.getMessage());
        }
    }

    /** 用户回复通知管理员（固定发给 admin 账号） */
    @Async
    public void notifyUserReply(Long feedbackId, String title, String noticeType) {
        try {
            User admin = userMapper.selectOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, ADMIN_USERNAME).last("limit 1"));
            if (admin == null) {
                log.warn("[FeedbackPlugin] 未找到管理员账号 {}，跳过用户回复通知", ADMIN_USERNAME);
                return;
            }
            SysNotice n = new SysNotice();
            n.setChannel(SysNotice.CHANNEL_APP);
            n.setNoticeType(noticeType);
            n.setUserId(admin.getId());
            n.setFeedbackId(feedbackId);
            n.setDisplay(DISPLAY_MESSAGE_CENTER);
            n.setTitle("用户在反馈「" + title + "」中回复");
            n.setContent("用户回复了反馈「" + title + "」，请及时处理。");
            n.setAudience("ALL");
            sysNoticeMapper.insert(fillDefault(n));
            log.info("[FeedbackPlugin] 用户回复通知已生成 feedbackId={}, adminId={}", feedbackId, admin.getId());
        } catch (Exception e) {
            log.warn("[FeedbackPlugin] 用户回复通知生成失败: {}", e.getMessage());
        }
    }

    // ==================== 私有工具 ====================

    private SysNotice fillDefault(SysNotice n) {
        if (n.getId() == null) {
            n.setId(feedbackSequenceService.nextId("sys_notice"));
        }
        n.setIsShow(1L);
        n.setIsTop(0L);
        n.setDialogClosable(1L);
        n.setFirstLoginOnly(0L);
        n.setMarquee(0L);
        n.setCreateTime(LocalDateTime.now());
        n.setUpdateTime(LocalDateTime.now());
        return n;
    }

    /** 计算保留期起始时间；配置为 0/空/解析失败 = 不限（返回 null） */
    private LocalDateTime retentionFrom() {
        String raw = null;
        try {
            raw = sysConfigService.getConfigValue(KEY_RETENTION_DAYS);
        } catch (Exception ignored) {
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int days = Integer.parseInt(raw.trim());
            if (days <= 0) {
                return null;
            }
            return LocalDateTime.now().minusDays(days);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 规范化端标识：仅 app | pc | web 合法，空值或未知值回退 app */
    public static String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return SysNotice.CHANNEL_APP;
        }
        return switch (channel.trim().toLowerCase(Locale.ROOT)) {
            case SysNotice.CHANNEL_PC -> SysNotice.CHANNEL_PC;
            case SysNotice.CHANNEL_WEB -> SysNotice.CHANNEL_WEB;
            default -> SysNotice.CHANNEL_APP;
        };
    }

    private boolean inEffectiveWindow(SysNotice n, LocalDateTime now) {
        if (n.getEffectiveStart() != null && now.isBefore(n.getEffectiveStart())) return false;
        if (n.getEffectiveEnd() != null && now.isAfter(n.getEffectiveEnd())) return false;
        return true;
    }

    private boolean inVersionRange(SysNotice n, String appVersion) {
        if (appVersion == null || appVersion.isBlank()) return true;
        Long current;
        try {
            current = Long.parseLong(appVersion.trim());
        } catch (NumberFormatException e) {
            return true;
        }
        Long min = n.getVersionMin();
        Long max = n.getVersionMax();
        if (min != null && current < min) return false;
        if (max != null && current > max) return false;
        return true;
    }

    private boolean audienceMatches(String audience, boolean loggedIn) {
        if (audience == null || audience.isBlank() || "ALL".equalsIgnoreCase(audience)) {
            return true;
        }
        if ("LOGGED_IN".equalsIgnoreCase(audience)) {
            return loggedIn;
        }
        if ("NOT_LOGGED_IN".equalsIgnoreCase(audience)) {
            return !loggedIn;
        }
        return true;
    }

    private boolean hasDisplay(Long display, long bit) {
        return display != null && (display & bit) == bit;
    }
}
