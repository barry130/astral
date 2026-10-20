package com.astral.feedback.service;

import com.astral.common.constant.NoticeConstants;
import com.astral.feedback.common.NoticeChannel;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.notify.NotifyPublisher;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
        notice.setChannel(NotifyEventRegistry.isFeedbackReply(notice.getScene())
                ? NoticeConstants.CHANNEL_ALL : NoticeChannel.normalizeForStore(notice.getChannel()));
        applyNotNullDefaults(notice);
        clampReadSeconds(notice);
        clampDisclaimerChannel(notice);
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        sysNoticeMapper.insert(notice);
        evictNoticeCache();
        return notice;
    }

    /**
     * 读秒时长上限（秒）。与后台表单 {@code InputNumber max={60}} 同值，此处再兜一次：
     * 表单能拦住的只有走界面的操作，直接调 {@code POST /admin/messages} 或脚本写库仍可塞大值。
     * <p>为什么必须有上限：这个读秒对应 qt-pc 的全屏免责声明弹窗，**没有关闭键**，
     * 用户只能等读秒走完 —— 写个 600 就等于把所有新用户首次启动锁十分钟。
     * 小数点与负数一律归 0（0 = 不读秒，确认键立即可点，最安全的一侧）。</p>
     */
    private static final long MAX_READ_SECONDS = 60L;

    /** 读秒夹到 [0, 60]；null 保持 null（= 不读秒） */
    private static void clampReadSeconds(SysNotice notice) {
        Long v = notice.getReadSeconds();
        if (v == null) return;
        notice.setReadSeconds(Math.min(MAX_READ_SECONDS, Math.max(0L, v)));
    }

    /**
     * 免责声明一律只投 App 六端
     * （{@code app-android / app-ios / app-windows / app-linux / app-macos / app-harmony}，不含 {@code web}），
     * 并强制 {@code dialog_closable=1}。
     *
     * <p><b>为什么渠道必须在服务端强制：</b>客户端不登记 terms_version 就会把声明当成普通开屏公告，
     * 而后台一键模板填的是 {@code dialog_closable=0} —— 那个开屏弹窗里「我知道了」在不可关闭时
     * 执行的是 {@code exitApp()}。所以不能只依赖后台勾选：
     * ① 各端 {@code X-App-Ut} 跟着编译目标走（Windows→{@code app-windows}、鸿蒙原生包→{@code app-harmony}……），
     * 少勾一个平台，那个平台的用户就永远收不到声明；
     * ② 老版本 APK 完全不可控，只勾了具体平台也可能被改错。
     * 因此这里不是「取交集」，而是**直接改成 App 六端全选**。</p>
     *
     * <p><b>为什么同时强制可关闭：</b>3.0.7 及以前的 qt-uniappx 不认识 terms_version，
     * 会把这条公告当普通开屏公告走 {@code qt-notice-splash}；若 {@code dialog_closable=0}，
     * 用户在 {@code onKnow()} 里点「我知道了」执行 {@code exitApp()}，点一下 App 就退出，
     * 且每次启动都再弹一次。强制可关闭把这个崩溃路径堵死 —— 新客户端本来就不走 splash
     * （qt-pc 按「开屏位 + termsVersion 非空」认领，不会读到这一位），所以对它们是零影响。</p>
     *
     * <p>非声明公告（terms_version 为空）不受影响：渠道与 dialog_closable 都仍按后台所选投放。</p>
     */
    private static void clampDisclaimerChannel(SysNotice notice) {
        String terms = notice.getTermsVersion();
        if (terms == null || terms.isBlank()) return;
        String want = NoticeChannel.allAppValue();
        if (!want.equals(notice.getChannel())) {
            log.info("[FeedbackPlugin] 免责声明渠道按规则改写为 App 六端：{} -> {}",
                    notice.getChannel(), want);
        }
        notice.setChannel(want);
        // 老 APK 会把声明当普通开屏公告；dialog_closable=0 时它的「我知道了」= exitApp()
        notice.setDialogClosable(1L);
    }

    /**
     * 非空列的空值回落（create / update 共用）。
     * <p>这些列库里虽有 DEFAULT，但显式写 NULL 仍会撞非空约束，所以送 SQL 前先回落默认值。</p>
     */
    private static void applyNotNullDefaults(SysNotice notice) {
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
    }

    /**
     * 更新通知：编辑表单提交什么就写什么（清空即写 NULL）。
     * <p>用白名单 {@link LambdaUpdateWrapper} 逐字段 set，而不是整实体 {@code updateById}，原因有二：</p>
     * <ul>
     *   <li><b>可空列要能清空</b>：版本码区间（{@code version_min}/{@code version_max}）、生效时间、
     *       {@code url}/{@code user_id} 被表单清空时必须真的写回 NULL。MyBatis-Plus 的 {@code updateById}
     *       默认 {@code updateStrategy = FieldStrategy.NOT_NULL}（{@code GlobalConfig.DbConfig}），
     *       生成 SQL 时经 {@code TableFieldInfo#convertIf} 包一层 {@code <if test="x != null">}，
     *       显式置 null 的列会被整条跳过 —— 于是「之前填过的版本号清不掉」，保存看似成功实则没生效。</li>
     *   <li>整实体更新还留了 mass assignment 面（客户端可塞 {@code scene}/{@code feedback_id}/{@code read_time}
     *       等非表单字段），详见 docs/CODING_GUIDE.md「不要整实体 updateById」。</li>
     * </ul>
     * <p>{@code channel} 提交为空仍表示「本次不改该字段」；{@code scene}/{@code feedback_id}/{@code read_time}
     * 由站内信与反馈事件写入、编辑表单不提交，故不在白名单内。</p>
     */
    public void update(Long id, SysNotice notice) {
        applyNotNullDefaults(notice);
        clampReadSeconds(notice);
        // 先按「是不是免责声明」定渠道，再进白名单分支：命中的渠道必须落库，
        // 否则编辑一条已有声明时（表单没提交 channel）会留着旧的手机端投放值。
        clampDisclaimerChannel(notice);
        LambdaUpdateWrapper<SysNotice> uw = new LambdaUpdateWrapper<SysNotice>()
                .eq(SysNotice::getId, id);
        // 事件归属取数据库行，避免编辑请求修改 scene 绕过回复通知的全平台约束。
        SysNotice existing = sysNoticeMapper.selectById(id);
        if (existing != null && NotifyEventRegistry.isFeedbackReply(existing.getScene())) {
            uw.set(SysNotice::getChannel, NoticeConstants.CHANNEL_ALL);
        } else if (notice.getChannel() != null && !notice.getChannel().isBlank()) {
            uw.set(SysNotice::getChannel, NoticeChannel.normalizeForStore(notice.getChannel()));
        }
        uw.set(SysNotice::getNoticeType, notice.getNoticeType())
                .set(SysNotice::getTitle, notice.getTitle())
                .set(SysNotice::getContent, notice.getContent())
                .set(SysNotice::getUrl, notice.getUrl())
                .set(SysNotice::getUserId, notice.getUserId())
                .set(SysNotice::getDisplay, notice.getDisplay())
                .set(SysNotice::getAudience, notice.getAudience())
                .set(SysNotice::getIsShow, notice.getIsShow())
                .set(SysNotice::getIsTop, notice.getIsTop())
                .set(SysNotice::getDialogClosable, notice.getDialogClosable())
                .set(SysNotice::getFirstLoginOnly, notice.getFirstLoginOnly())
                .set(SysNotice::getMarquee, notice.getMarquee())
                // 首次启动免责声明专用两列（可空，清空即写 NULL = 不读秒 / 无版本号）
                .set(SysNotice::getReadSeconds, notice.getReadSeconds())
                .set(SysNotice::getTermsVersion, notice.getTermsVersion())
                .set(SysNotice::getEffectiveStart, notice.getEffectiveStart())
                .set(SysNotice::getEffectiveEnd, notice.getEffectiveEnd())
                .set(SysNotice::getVersionMin, notice.getVersionMin())
                .set(SysNotice::getVersionMax, notice.getVersionMax())
                .set(SysNotice::getUpdateTime, LocalDateTime.now());
        sysNoticeMapper.update(null, uw);
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
