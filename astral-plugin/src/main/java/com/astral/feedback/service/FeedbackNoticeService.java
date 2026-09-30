package com.astral.feedback.service;

import com.astral.dao.entity.Role;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;
import com.astral.feedback.common.NoticeChannel;
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
import java.util.List;
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

    @Resource
    private SysNoticeMapper sysNoticeMapper;

    @Resource
    private SysConfigService sysConfigService;

    @Resource
    private UserMapper userMapper;

    @Resource
    private RoleMapper roleMapper;

    @Resource
    private UserRoleMapper userRoleMapper;

    /**
     * 当前生效通知列表（App 端：Android + iOS，公开，三展示位共用）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForApp(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(SysNotice.CHANNEL_ANDROID, SysNotice.CHANNEL_IOS), appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（PC / 桌面端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForPc(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(SysNotice.CHANNEL_WINDOWS), appVersion, loggedIn, userId);
    }

    /**
     * 当前生效通知列表（Web 端，公开）
     *
     * @param appVersion 客户端版本码（如 300），可为空
     * @param loggedIn   是否已登录
     * @param userId     已登录时的用户ID（用于点对点过滤）
     */
    public List<SysNotice> listForWeb(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(SysNotice.CHANNEL_WEB), appVersion, loggedIn, userId);
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
        LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<>();
        qw.eq(SysNotice::getIsShow, 1L);
        // 保留期：announce 不受限；其余类型仅最近 N 天（N=0/空=不限）
        if (retentionFrom != null) {
            qw.and(w -> w.eq(SysNotice::getNoticeType, SysNotice.TYPE_ANNOUNCE)
                    .or().ge(SysNotice::getCreateTime, retentionFrom));
        }
        qw.orderByDesc(SysNotice::getIsTop).orderByDesc(SysNotice::getCreateTime);
        // 渠道匹配刻意放在 Java 侧：channel 是多值逗号列，用 LIKE 会误命中（新平台值可能互为子串），
        // 用数据库数组/分隔符函数又绑死方言；公告表数据量小，全量取回后精确匹配更稳。
        List<SysNotice> all = sysNoticeMapper.selectList(qw);

        return all.stream()
                .filter(n -> NoticeChannel.matches(n.getChannel(), targets))
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
        return listMessageCenter(userId, List.of(SysNotice.CHANNEL_ANDROID, SysNotice.CHANNEL_IOS));
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
        return countUnread(userId, List.of(SysNotice.CHANNEL_ANDROID, SysNotice.CHANNEL_IOS));
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
        notice.setCreateTime(LocalDateTime.now());
        notice.setUpdateTime(LocalDateTime.now());
        sysNoticeMapper.insert(notice);
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
            n.setChannel(SysNotice.CHANNEL_MOBILE);
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
            n.setChannel(SysNotice.CHANNEL_MOBILE);
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
            n.setChannel(SysNotice.CHANNEL_MOBILE);
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

    /** 用户回复通知管理端（群发给所有 ADMIN 角色用户，每人一条点对点） */
    @Async
    public void notifyUserReply(Long feedbackId, String title, String noticeType) {
        try {
            for (Long adminId : adminUserIds()) {
                SysNotice n = new SysNotice();
                n.setChannel(SysNotice.CHANNEL_WINDOWS);
                n.setNoticeType(noticeType);
                n.setUserId(adminId);
                n.setFeedbackId(feedbackId);
                n.setDisplay(DISPLAY_MESSAGE_CENTER);
                n.setTitle("用户在反馈「" + title + "」中回复");
                n.setContent("用户回复了反馈「" + title + "」，请及时处理。");
                n.setAudience("ALL");
                sysNoticeMapper.insert(fillDefault(n));
            }
            log.info("[FeedbackPlugin] 用户回复通知已群发 ADMIN 角色 feedbackId={}", feedbackId);
        } catch (Exception e) {
            log.warn("[FeedbackPlugin] 用户回复通知生成失败: {}", e.getMessage());
        }
    }

    /** 新反馈/需求提交通知管理端（群发给所有 ADMIN 角色用户） */
    @Async
    public void notifyNewFeedback(Long feedbackId, String title, String noticeType) {
        try {
            String label = SysNotice.TYPE_REQUEST.equals(noticeType) ? "新需求" : "新反馈";
            for (Long adminId : adminUserIds()) {
                SysNotice n = new SysNotice();
                n.setChannel(SysNotice.CHANNEL_WINDOWS);
                n.setNoticeType(noticeType);
                n.setUserId(adminId);
                n.setFeedbackId(feedbackId);
                n.setDisplay(DISPLAY_MESSAGE_CENTER);
                n.setTitle(label + "「" + title + "」已提交");
                n.setContent("用户提交了" + label + "「" + title + "」，请及时处理。");
                n.setAudience("ALL");
                sysNoticeMapper.insert(fillDefault(n));
            }
            log.info("[FeedbackPlugin] 新反馈通知已群发 ADMIN 角色 feedbackId={}", feedbackId);
        } catch (Exception e) {
            log.warn("[FeedbackPlugin] 新反馈通知生成失败: {}", e.getMessage());
        }
    }

    /**
     * 管理端收件箱（顶栏铃铛数据源）
     * <p>与 App 端消息中心的差异：不按 channel 过滤（app/pc/web/all 全可见）、
     * 忽略版本码区间（那是 App 客户端概念）；其余过滤链一致：
     * is_show=1 → 生效时间窗 → audience → 广播或点对点 → display 含消息中心(4) → 保留期。</p>
     *
     * @param adminUserId 当前管理端登录用户ID（点对点通知按此过滤）
     */
    public List<SysNotice> listAdminInbox(Long adminUserId) {
        LocalDateTime retentionFrom = retentionFrom();
        LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<>();
        qw.eq(SysNotice::getIsShow, 1L);
        if (retentionFrom != null) {
            qw.and(w -> w.eq(SysNotice::getNoticeType, SysNotice.TYPE_ANNOUNCE)
                    .or().ge(SysNotice::getCreateTime, retentionFrom));
        }
        qw.orderByDesc(SysNotice::getIsTop).orderByDesc(SysNotice::getCreateTime);
        return sysNoticeMapper.selectList(qw).stream()
                .filter(n -> inEffectiveWindow(n, LocalDateTime.now()))
                .filter(n -> audienceMatches(n.getAudience(), true))
                .filter(n -> n.getUserId() == null || (adminUserId != null && adminUserId.equals(n.getUserId())))
                .filter(n -> hasDisplay(n.getDisplay(), DISPLAY_MESSAGE_CENTER))
                .collect(Collectors.toList());
    }

    /** 查询所有启用且未删除的 ADMIN 角色用户ID（管理侧通知的群发对象） */
    private List<Long> adminUserIds() {
        Role role = roleMapper.selectOne(new LambdaQueryWrapper<Role>()
                .eq(Role::getRoleCode, ROLE_CODE_ADMIN).last("limit 1"));
        if (role == null) {
            log.warn("[FeedbackPlugin] 未找到角色编码 {}，跳过管理侧通知", ROLE_CODE_ADMIN);
            return List.of();
        }
        List<Long> userIds = userRoleMapper.selectList(new LambdaQueryWrapper<UserRole>()
                        .eq(UserRole::getRoleId, role.getId()))
                .stream().map(UserRole::getUserId).distinct().collect(Collectors.toList());
        if (userIds.isEmpty()) {
            return List.of();
        }
        // 仅通知启用且未删除的账号
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .in(User::getId, userIds)
                        .eq(User::getStatus, 1)
                        .eq(User::getDeleted, 0))
                .stream().map(User::getId).collect(Collectors.toList());
    }

    // ==================== 私有工具 ====================

    private SysNotice fillDefault(SysNotice n) {
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
