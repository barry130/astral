package com.astral.system.notice;

import com.astral.common.constant.NoticeConstants;
import com.astral.dao.entity.SysNotice;
import com.astral.dao.mapper.SysNoticeMapper;
import com.astral.system.notify.NotifyEventRegistry;
import com.astral.system.service.SysConfigService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 站内通知服务实现：App 端通知的查询过滤链 + 管理端公告/通知 CRUD。
 *
 * <p>本类<b>不含任何事件触发逻辑</b>：通知的投递（点对点落表）由订阅规则 +
 * {@code NotifyPublisher} → {@code InappService} 完成，业务插件（反馈等）只在
 * 业务节点触发事件。宿主的通知能力因此不再依赖任何插件是否启用。</p>
 */
@Slf4j
@Service
public class NoticeServiceImpl implements NoticeService {

    /** 保留期配置键 */
    private static final String KEY_RETENTION_DAYS = "notice.feedback.retention_days";
    /** 默认保留天数（0 或空 = 不限） */
    private static final int DEFAULT_RETENTION_DAYS = 7;

    /** 生效通知原始行缓存键（单键，全端共用一份） */
    private static final String CACHE_KEY_ACTIVE = "activeNotices";

    /**
     * is_show=1 通知原始行的进程内缓存（单键，TTL 60s）。
     * <p>App/PC/Web 三端启动通知轮询共用 {@link #listForChannel} 这条读路径，
     * 原先每次请求都全量 selectList。过滤链（渠道/时间窗/版本/人群/点对点）
     * 全部在内存完成，缓存实体只读不写。管理端 CRUD 写入后都调用
     * {@link #evictNoticeCache()} 立即失效，编辑/新增的公告不会等 TTL。</p>
     * <p>事件触发的点对点通知由 {@code InappService} 写同一张表，走 60s TTL 自然过期
     * （不在此处跨类回调失效，避免宿主 notify 包反向依赖 notice 包的服务实例）。</p>
     */
    private final Cache<String, List<SysNotice>> noticeCache = Caffeine.newBuilder()
            .maximumSize(4)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    @Resource
    private SysNoticeMapper sysNoticeMapper;

    @Resource
    private SysConfigService sysConfigService;

    @Override
    public List<SysNotice> listForApp(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(NoticeConstants.CHANNEL_ANDROID, NoticeConstants.CHANNEL_IOS), appVersion, loggedIn, userId);
    }

    @Override
    public List<SysNotice> listForPc(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(NoticeConstants.CHANNEL_WINDOWS), appVersion, loggedIn, userId);
    }

    @Override
    public List<SysNotice> listForWeb(String appVersion, boolean loggedIn, Long userId) {
        return listForChannel(List.of(NoticeConstants.CHANNEL_WEB), appVersion, loggedIn, userId);
    }

    @Override
    public List<SysNotice> listForChannel(List<String> targets, String appVersion, boolean loggedIn, Long userId) {
        LocalDateTime retentionFrom = retentionFrom();
        // 渠道匹配刻意放在 Java 侧：channel 是多值逗号列，用 LIKE 会误命中（新平台值可能互为子串），
        // 用数据库数组/分隔符函数又绑死方言；公告表数据量小，全量取回后精确匹配更稳。
        List<SysNotice> all = listActiveNotices(retentionFrom);

        return all.stream()
                .filter(n -> NoticeTargets.matches(n.getChannel(), targets))
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
            // 保留期：公告(announce)不受限；其余类型（通知）仅最近 N 天（N=0/空=不限）
            if (retentionFrom != null) {
                qw.and(w -> w.eq(SysNotice::getNoticeType, NoticeConstants.TYPE_ANNOUNCE)
                        .or().ge(SysNotice::getCreateTime, retentionFrom));
            }
            qw.orderByDesc(SysNotice::getIsTop).orderByDesc(SysNotice::getCreateTime);
            return sysNoticeMapper.selectList(qw);
        });
    }

    @Override
    public void evictNoticeCache() {
        noticeCache.invalidateAll();
    }

    @Override
    public List<SysNotice> listMessageCenter(Long userId) {
        return listMessageCenter(userId, List.of(NoticeConstants.CHANNEL_ANDROID, NoticeConstants.CHANNEL_IOS));
    }

    @Override
    public List<SysNotice> listMessageCenter(Long userId, List<String> targets) {
        return listForChannel(targets, null, userId != null, userId).stream()
                .filter(n -> NoticeDisplay.hasDisplay(n.getDisplay(), NoticeDisplay.DISPLAY_MESSAGE_CENTER))
                .collect(Collectors.toList());
    }

    @Override
    public long countUnread(Long userId) {
        return countUnread(userId, List.of(NoticeConstants.CHANNEL_ANDROID, NoticeConstants.CHANNEL_IOS));
    }

    @Override
    public long countUnread(Long userId, List<String> targets) {
        return listMessageCenter(userId, targets).size();
    }

    @Override
    public void readAck(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        log.info("[Notice] 用户 {} 已读回执 {} 条: {}", userId, ids.size(), ids);
    }

    // ==================== 管理端 ====================

    @Override
    public Page<SysNotice> page(int pageNum, int pageSize, String channel, String noticeType,
                                String keyword, String startDate, String endDate) {
        Page<SysNotice> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<>();
        List<String> channelFilters = NoticeTargets.parseFilter(channel);
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
            // 类型口径已收敛为「公告 / 通知」两类：
            // 选「通知」要把历史 feedback/request 行一并带出来（存量 297 行不做数据迁移，
            // 老客户端仍按这两个值渲染标签）；「公告」及其它值保持精确匹配。
            if (NoticeConstants.TYPE_NOTICE.equals(noticeType)) {
                qw.in(SysNotice::getNoticeType, NoticeConstants.TYPE_NOTICE,
                        NoticeConstants.TYPE_LEGACY_FEEDBACK, NoticeConstants.TYPE_LEGACY_REQUEST);
            } else {
                qw.eq(SysNotice::getNoticeType, noticeType);
            }
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

    @Override
    public SysNotice create(SysNotice notice) {
        notice.setChannel(NotifyEventRegistry.isFeedbackReply(notice.getScene())
                ? NoticeConstants.CHANNEL_ALL : NoticeTargets.normalizeForStore(notice.getChannel()));
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
        String want = NoticeTargets.allAppValue();
        if (!want.equals(notice.getChannel())) {
            log.info("[Notice] 免责声明渠道按规则改写为 App 六端：{} -> {}", notice.getChannel(), want);
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
            // 新建不指定类型时回落「公告」（§4.3：这条逻辑保持不变）
            notice.setNoticeType(NoticeConstants.TYPE_ANNOUNCE);
        }
        if (notice.getDisplay() == null) {
            notice.setDisplay(NoticeDisplay.DISPLAY_MESSAGE_CENTER);
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
    @Override
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
            uw.set(SysNotice::getChannel, NoticeTargets.normalizeForStore(notice.getChannel()));
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

    @Override
    public void delete(Long id) {
        sysNoticeMapper.deleteById(id);
        evictNoticeCache();
    }

    @Override
    public List<SysNotice> listAdminInbox(Long adminId) {
        return sysNoticeMapper.selectList(new LambdaQueryWrapper<SysNotice>()
                .eq(SysNotice::getIsShow, 1L)
                .and(w -> w.isNull(SysNotice::getUserId).or().eq(SysNotice::getUserId, adminId))
                .orderByDesc(SysNotice::getIsTop)
                .orderByDesc(SysNotice::getCreateTime)
                .last("limit 50"));
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
}
