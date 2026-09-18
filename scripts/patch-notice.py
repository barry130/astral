# -*- coding: utf-8 -*-
"""一次性脚本：通知改造——ADMIN 角色群发 + 新反馈提交通知 + 管理端收件箱。"""
import io

def patch(path, pairs):
    with io.open(path, 'r', encoding='utf-8') as f:
        t = f.read()
    for old, new in pairs:
        assert t.count(old) == 1, (path, t.count(old), old[:70])
        t = t.replace(old, new)
    with io.open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(t)
    print('patched', path)

# ============ 1. FeedbackNoticeService ============
patch('astral-plugin/src/main/java/com/astral/feedback/service/FeedbackNoticeService.java', [
("""import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;""",
 """import com.astral.dao.entity.Role;
import com.astral.dao.entity.User;
import com.astral.dao.entity.UserRole;
import com.astral.dao.mapper.RoleMapper;
import com.astral.dao.mapper.UserMapper;
import com.astral.dao.mapper.UserRoleMapper;"""),

("""    /** 通知管理员固定账号 */
    private static final String ADMIN_USERNAME = "admin";

    @Resource
    private SysNoticeMapper sysNoticeMapper;

    @Resource
    private SysConfigService sysConfigService;

    @Resource
    private UserMapper userMapper;""",
 """    /** 管理端角色编码：拥有该角色的用户接收管理侧通知（新反馈/用户回复） */
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
    private UserRoleMapper userRoleMapper;"""),

("""    /** 用户回复通知管理员（固定发给 admin 账号） */
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
    }""",
 """    /** 用户回复通知管理端（群发给所有 ADMIN 角色用户，每人一条点对点） */
    @Async
    public void notifyUserReply(Long feedbackId, String title, String noticeType) {
        try {
            for (Long adminId : adminUserIds()) {
                SysNotice n = new SysNotice();
                n.setChannel(SysNotice.CHANNEL_PC);
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
                n.setChannel(SysNotice.CHANNEL_PC);
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
    }"""),
])

# ============ 2. FeedbackService.submit ============
patch('astral-plugin/src/main/java/com/astral/feedback/service/FeedbackService.java', [
("""        f.setUpdateTime(LocalDateTime.now());
        feedbackMapper.insert(f);
        return f;
    }""",
 """        f.setUpdateTime(LocalDateTime.now());
        feedbackMapper.insert(f);
        // 异步通知管理端（所有 ADMIN 角色用户）
        noticeService.notifyNewFeedback(f.getId(), f.getTitle(), toNoticeType(f.getType()));
        return f;
    }"""),
])

# ============ 3. AdminMessageController：/inbox ============
patch('astral-plugin/src/main/java/com/astral/feedback/controller/AdminMessageController.java', [
("""    @Operation(summary = "通知分页（channel/notice_type/关键词/时间筛选）")""",
 """    @Operation(summary = "管理端收件箱（顶栏铃铛数据源：不按 channel 过滤，广播+发给当前管理员的点对点）")
    @GetMapping("/inbox")
    public Result<List<SysNotice>> inbox() {
        return Result.success(noticeService.listAdminInbox(currentAdminId()));
    }

    /** 当前管理端登录用户ID（宿主 Sa-Token） */
    private Long currentAdminId() {
        Object loginId = cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new com.astral.feedback.common.FeedbackException(401, "登录状态已失效");
        }
        return Long.parseLong(loginId.toString());
    }

    @Operation(summary = "通知分页（channel/notice_type/关键词/时间筛选）")"""),
])

print('all backend patches done')
