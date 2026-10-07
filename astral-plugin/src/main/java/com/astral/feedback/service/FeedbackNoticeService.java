package com.astral.feedback.service;

import com.astral.common.constant.NoticeConstants;
import com.astral.feedback.common.NoticeChannel;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.notify.NotifyPublisher;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

    /** 管理端角色编码：拥有该角色的用户接收管理侧通知（新反馈/用户回复） */
    private static final String ROLE_CODE_ADMIN = "ADMIN";

    /** 生效通知原始行缓存键（单键，全端共用一份） */
    private static final String CACHE_KEY_ACTIVE = "activeNotices";

    /**
     * is_show=1 通知原始行的进程内缓存（单键，TTL 60s）。
     * <p>App/PC/Web 三端启动通知轮询共用 {@link #listForChannel} 这条读路径，
     * 原先每次请求都全量 selectList。过滤链（渠道/时间窗/版本/人群/点对点）
     * 全部在内存完成，缓存实体只读不写。所有写入（管理端 CRUD + 反馈事件
     * 触发的点对点通知 insert）都调用 {@link #evictNoticeCache()} 立即失效，
     * 收件人不受 TTL 延迟。</p>
     */
    private final Cache<String, List<SysNotice>> noticeCache = Caffeine.newBuilder()
            .maximumSize(4)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    @Resource
    private SysNoticeMapper sysNoticeMapper;

    @Resource
    private SysConfigService sysConfigService;

    /** 通知触发走平台事件发布器（订阅规则决定收件人/渠道/文案，本类只声明事件与上下文） */
    @Resource
    private NotifyPublisher notifyPublisher;

    /**
     * 当前生效通知列表（App 端：Android + iOS，公开，三展示位共用）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForApp(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(NoticeConstants.CHANNEL_ANDROID, NoticeConstants.CHANNEL_IOS), appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（PC / 桌面端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForPc(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(NoticeConstants.CHANNEL_WINDOWS), appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（Web 端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForWeb(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(NoticeConstants.CHANNEL_WEB), appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（指定平台集合，公开）
     * <p>过滤链（§3.3）：is_show=1 → channel 命中 → 时间窗 → 版本码区间 → audience → 广播或点对点 → 保留期。</p>
     *
     * @param targets    待匹配平台集合，来自 {@link NoticeChannel#resolveTargets}；
     *                   含 {@link NoticeChannel#ALL} 表示不限平台
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForChannel(List<String> targets, String appVersion, boolean loggedIn, Long userId) {
        LocalDateTime retentionFrom = retentionFrom();
        // 渠道匹配刻意放在 Java 侧：channel 是多值逗号列，用 LIKE 会误命中（新平台值可能互为子串），
        // 用数据库数组/分隔符函数又绑死方言；公告表数据量小，全量取回后精确匹配更稳。
        List<SysNotice> all = listActiveNotices(retentionFrom);

        return all.stream()
                .filter(n -> NoticeChannel.matches(n.getChannel(), targets))
                .filter(n -> inEffectiveWindow(n, LocalDateTime.now()))
                .filter(n -> inVersionRange(n, appVersion))
                .filter(n -> audienceMatches(n.getAudience(), loggedIn))
                .filter(n -> n.getUserId() == null || (userId != null && userId.equals(n.getUserId())))
                .collect(Collectors.toList());
    }

    /** is_show=1（含保留期窗口）的原始通知行：单键进程内缓存，写路径主动失效 + 60s TTL 兜底 */
    private List<SysNotice> listActiveNotices(LocalDateTime retentionFrom) {
        return noticeCache.get(CACHE_KEY_ACTIVE, k -> {
            LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<>();
            qw.eq(SysNotice::getIsShow, 1L);
            // 保留期：announce 不受限；其余类型仅最近 N 天（N=0/空=不限）
            if (retentionFrom != null) {
                qw.and(w -> w.eq(SysNotice::getNoticeType, NoticeConstants.TYPE_ANNOUNCE)
                        .or().ge(SysNotice::getCreateTime, retentionFrom));
            }
            qw.orderByDesc(SysNotice::getIsTop).orderByDesc(SysNotice::getCreateTime);
            return sysNoticeMapper.selectList(qw);
        });
    }

    /** 任何通知写入（管理端 CRUD / 反馈事件触发）后调用：立即失效生效通知缓存 */
    public void evictNoticeCache() {
        noticeCache.invalidateAll();
    }

    /**
     * 消息中心列表（App 端）
     * <p>在 listForApp 基础上追加 display 含消息中心(4)，返回带 noticeType 供前端区分标签。</p>
     * <p>已读状态由前端缓存判断，后端不返回 read 字段。</p>
     */
    public List<SysNotice> listMessageCenter(Long userId) {
        return listMessageCenter(userId, List.of(NoticeConstants.CHANNEL_ANDROID, NoticeConstants.CHANNEL_IOS));
    }

    /**
     * 消息中心列表（指定平台集合）
     * <p>在 listForChannel 基础上追加 display 含消息中心(4)。</p>
     */
    public List<SysNotice> listMessageCenter(Long userId, List<String> targets) {
        return listForChannel(targets, null, userId != null, userId).stream()
                .filter(n -> hasDisplay(n.getDisplay(), DISPLAY_MESSAGE_CENTER))
                .collect(Collectors.toList());
    }

    /**
     * 未读数（候选总数，App 端）
     * <p>返回该用户可见消息中心条目总数；已读判定在前端缓存，清缓存=全部未读（既定决策 D6）。</p>
     */
    public long countUnread(Long userId) {
        return countUnread(userId, List.of(NoticeConstants.CHANNEL_ANDROID, NoticeConstants.CHANNEL_IOS));
    }

    /**
     * 未读数（候选总数，指定平台集合）
     */
    public long countUnread(Long userId, List<String> targets) {
        return listMessageCenter(userId, targets).size();
    }

    /** 已读回执（本期仅记日志，不落表） */
    public void readAck(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        log.info("[FeedbackPlugin] 用户 {} 已读回执 {} 条: {}", userId, ids.size(), ids);
    }

    // ==================== 管理端 ====================

    /**
     * 通知分页（channel/notice_type/关键词/时间筛选）
     *
     * @param channel 渠道筛选，支持多选（逗号分隔平台值），兼容遗留单值 app/pc/web/all
     */
    public Page<SysNotice> page(int pageNum, int pageSize, String channel, String noticeType,
                                String keyword, String startDate, String endDate) {
        Page<SysNotice> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<>();
        List<String> channelFilters = NoticeChannel.parseFilter(channel);
        if (!channelFilters.isEmpty()) {
            // 多值列没法用等值匹配；平台值之间互不为子串，用 OR LIKE 作为筛选近似（管理端筛选，非权限判定）。
            // 首个条件不加 or()，避免生成以 OR 开头的非法片段。
            qw.and(w -> {
                for (int i = 0; i < channelFilters.size(); i++) {
                    if (i == 0) {
                        w.like(SysNotice::getChannel, channelFilters.get(i));
                    } else {
                        w.or().like(SysNotice::getChannel, channelFilters.get(i));
                    }
                }
            });
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

    /** 新增通知（公告/定向）；渠道归一化与校验统一走 {@link NoticeChannel} */
    public SysNotice create(SysNotice notice) {
        notice.setChannel(NoticeChannel.normalizeForStore(notice.getChannel()));
        if (notice.getNoticeType() == null || notice.getNoticeType().isBlank()) {
            notice.setNoticeType(NoticeConstants.TYPE_ANNOUNCE);
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
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        sysNoticeMapper.insert(notice);
        evictNoticeCache();
        return notice;
    }

    /** 更新通知 */
    public void update(Long id, SysNotice notice) {
        notice.setId(id);
        // 仅当提交了渠道才归一化：为空表示「本次不改该字段」，不能回落成默认值把原渠道冲掉
        if (notice.getChannel() != null && !notice.getChannel().isBlank()) {
            notice.setChannel(NoticeChannel.normalizeForStore(notice.getChannel()));
        } else {
            notice.setChannel(null);
        }
        notice.setUpdateTime(LocalDateTime.now());
        sysNoticeMapper.updateById(notice);
        evictNoticeCache();
    }

    /** 删除通知（物理删） */
    public void delete(Long id) {
        sysNoticeMapper.deleteById(id);
        evictNoticeCache();
    }

    /**
     * 管理端收件箱（顶栏铃铛数据源）：广播 + 发给当前管理员的点对点，不按渠道过滤。
     * <p>统一通知存储后点对点行即 user_id 指向的行；只取 is_show=1，最近 50 条。</p>
     */
    public List<SysNotice> listAdminInbox(Long adminId) {
        return sysNoticeMapper.selectList(new LambdaQueryWrapper<SysNotice>()
                .eq(SysNotice::getIsShow, 1L)
                .and(w -> w.isNull(SysNotice::getUserId).or().eq(SysNotice::getUserId, adminId))
                .orderByDesc(SysNotice::getIsTop)
                .orderByDesc(SysNotice::getCreateTime)
                .last("limit 50"));
    }

    // ==================== 通知触发（异步，经订阅规则投递） ====================

    /**
     * 反馈状态变更通知提交人。
     * <p>文案在「消息中心-邮箱模板」（事件 {@link NotifyEventRegistry#FEEDBACK_STATUS_CHANGED}
     * 渠道 INAPP）维护；收件人/平台由订阅规则决定，本方法只发事件。</p>
     *
     * @param feedbackId 反馈ID
     * @param userId     提交人
     * @param title      反馈标题
     * @param noticeType 通知类型（feedback/request，App 端消息中心标签）
     * @param statusName 状态名（中文）
     */
    public void notifyStatusChange(Long feedbackId, Long userId, String title, String noticeType, String statusName) {
        notifyPublisher.publishAsync(NotifyEventRegistry.FEEDBACK_STATUS_CHANGED, Map.of(
                "feedbackId", String.valueOf(feedbackId),
                "userId", String.valueOf(userId),
                "feedbackTitle", nullToEmpty(title),
                "statusName", nullToEmpty(statusName),
                "noticeType", nullToEmpty(noticeType)));
    }

    /** 反馈已公开发布通知提交人（事件 {@link NotifyEventRegistry#FEEDBACK_PUBLISHED}） */
    public void notifyPublished(Long feedbackId, Long userId, String title, String noticeType) {
        notifyPublisher.publishAsync(NotifyEventRegistry.FEEDBACK_PUBLISHED, Map.of(
                "feedbackId", String.valueOf(feedbackId),
                "userId", String.valueOf(userId),
                "feedbackTitle", nullToEmpty(title),
                "noticeType", nullToEmpty(noticeType)));
    }

    /** 管理员回复通知提交人（事件 {@link NotifyEventRegistry#FEEDBACK_ADMIN_REPLIED}） */
    public void notifyAdminReply(Long feedbackId, Long userId, String title, String noticeType) {
        notifyPublisher.publishAsync(NotifyEventRegistry.FEEDBACK_ADMIN_REPLIED, Map.of(
                "feedbackId", String.valueOf(feedbackId),
                "userId", String.valueOf(userId),
                "feedbackTitle", nullToEmpty(title),
                "noticeType", nullToEmpty(noticeType)));
    }

    /** 用户回复通知管理端（群发给 ADMIN 角色，事件 {@link NotifyEventRegistry#FEEDBACK_USER_REPLIED}） */
    public void notifyUserReply(Long feedbackId, String title, String noticeType) {
        notifyPublisher.publishAsync(NotifyEventRegistry.FEEDBACK_USER_REPLIED, Map.of(
                "feedbackId", String.valueOf(feedbackId),
                "feedbackTitle", nullToEmpty(title),
                "noticeType", nullToEmpty(noticeType)));
    }

    /** 新反馈/需求提交通知管理端（群发给 ADMIN 角色，事件 {@link NotifyEventRegistry#FEEDBACK_NEW_SUBMISSION}） */
    public void notifyNewFeedback(Long feedbackId, String title, String noticeType) {
        notifyPublisher.publishAsync(NotifyEventRegistry.FEEDBACK_NEW_SUBMISSION, Map.of(
                "feedbackId", String.valueOf(feedbackId),
                "feedbackTitle", nullToEmpty(title),
                "noticeType", nullToEmpty(noticeType)));
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    // ==================== 私有工具 ====================

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
