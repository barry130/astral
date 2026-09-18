package com.astral.feedback.service;

import com.astral.dao.entity.User;
import com.astral.dao.mapper.UserMapper;
import com.astral.feedback.common.FeedbackException;
import com.astral.feedback.dto.PublicDto;
import com.astral.feedback.dto.ReplyDto;
import com.astral.feedback.dto.StatusDto;
import com.astral.feedback.entity.Feedback;
import com.astral.feedback.entity.FeedbackReply;
import com.astral.feedback.entity.SysNotice;
import com.astral.feedback.mapper.FeedbackMapper;
import com.astral.feedback.mapper.FeedbackReplyMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 反馈服务
 * <p>负责反馈的提交、查询、回复、状态流转、公开切换、软删与统计看板。
 * 状态流转/回复时触发 sys_notice 通知（异步）。</p>
 */
@Slf4j
@Service
public class FeedbackService {

    @Resource
    private FeedbackMapper feedbackMapper;

    @Resource
    private FeedbackReplyMapper replyMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private FeedbackNoticeService noticeService;

    /** 合法状态流转表 */
    private static final Map<String, List<String>> STATUS_TRANSITIONS = new HashMap<>();

    static {
        STATUS_TRANSITIONS.put(Feedback.STATUS_PENDING, List.of(Feedback.STATUS_RECEIVED, Feedback.STATUS_DEPRECATED));
        STATUS_TRANSITIONS.put(Feedback.STATUS_RECEIVED, List.of(Feedback.STATUS_RESOLVED, Feedback.STATUS_DEPRECATED));
        STATUS_TRANSITIONS.put(Feedback.STATUS_RESOLVED, List.of(Feedback.STATUS_PUBLISHED, Feedback.STATUS_DEPRECATED));
        STATUS_TRANSITIONS.put(Feedback.STATUS_PUBLISHED, List.of(Feedback.STATUS_DEPRECATED));
        // 已废弃允许任意转回（防止误操作无法恢复）
        STATUS_TRANSITIONS.put(Feedback.STATUS_DEPRECATED, List.of(
                Feedback.STATUS_PENDING, Feedback.STATUS_RECEIVED,
                Feedback.STATUS_RESOLVED, Feedback.STATUS_PUBLISHED));
    }

    /** 状态中文名 */
    private static final Map<String, String> STATUS_NAMES = Map.of(
            Feedback.STATUS_PENDING, "提出",
            Feedback.STATUS_RECEIVED, "已接收",
            Feedback.STATUS_RESOLVED, "已解决",
            Feedback.STATUS_PUBLISHED, "已发布",
            Feedback.STATUS_DEPRECATED, "已废弃"
    );

    // ==================== App 端 ====================

    /** 提交反馈 */
    @Transactional
    public Feedback submit(Long userId, com.astral.feedback.dto.SubmitFeedbackDto dto,
                           String device, String os, String appVersion, String platform, String ip) {
        Feedback f = new Feedback();
        f.setUserId(userId);
        f.setType(normalizeType(dto.getType()));
        f.setTitle(dto.getTitle());
        f.setContent(dto.getContent());
        f.setContact(dto.getContact());
        f.setStatus(Feedback.STATUS_PENDING);
        f.setIsPublic(false);
        f.setDevice(device);
        f.setOs(os);
        f.setAppVersion(appVersion);
        f.setPlatform(platform);
        f.setIp(ip);
        f.setCreateTime(LocalDateTime.now());
        f.setUpdateTime(LocalDateTime.now());
        feedbackMapper.insert(f);
        // 异步通知管理端（所有 ADMIN 角色用户）
        noticeService.notifyNewFeedback(f.getId(), f.getTitle(), toNoticeType(f.getType()));
        return f;
    }

    /** 我的反馈（分页，全部状态，create_time DESC） */
    public Page<Feedback> myPage(Long userId, int pageNum, int pageSize) {
        Page<Feedback> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Feedback> qw = new LambdaQueryWrapper<>();
        qw.eq(Feedback::getUserId, userId);
        qw.isNull(Feedback::getDeleteTime);
        qw.orderByDesc(Feedback::getCreateTime);
        return feedbackMapper.selectPage(page, qw);
    }

    /** 公开列表（published AND is_public） */
    public Page<Feedback> publicPage(int pageNum, int pageSize) {
        Page<Feedback> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Feedback> qw = new LambdaQueryWrapper<>();
        qw.eq(Feedback::getStatus, Feedback.STATUS_PUBLISHED);
        qw.eq(Feedback::getIsPublic, true);
        qw.isNull(Feedback::getDeleteTime);
        qw.orderByDesc(Feedback::getCreateTime);
        return feedbackMapper.selectPage(page, qw);
    }

    /** 详情（本人 或 published+public，否则 403） */
    public Feedback detail(Long userId, Long id, boolean isAdmin) {
        Feedback f = feedbackMapper.selectById(id);
        if (f == null || f.getDeleteTime() != null) {
            throw new FeedbackException(300, "反馈不存在");
        }
        boolean publicVisible = Feedback.STATUS_PUBLISHED.equals(f.getStatus())
                && Boolean.TRUE.equals(f.getIsPublic());
        boolean self = userId != null && userId.equals(f.getUserId());
        if (!isAdmin && !publicVisible && !self) {
            throw new FeedbackException(403, "无权查看该反馈");
        }
        return f;
    }

    /** 回复列表（升序，JOIN 昵称/用户类型） */
    public List<FeedbackReply> replies(Long userId, Long id, boolean isAdmin) {
        // 先校验可见性
        detail(userId, id, isAdmin);
        return queryReplies(id);
    }

    /** 用户回复（同时通知管理员） */
    @Transactional
    public FeedbackReply userReply(Long userId, ReplyDto dto) {
        Feedback f = detail(userId, dto.getFeedbackId(), false);
        // 已废弃/已删除的反馈不允许回复
        if (Feedback.STATUS_DEPRECATED.equals(f.getStatus())) {
            throw new FeedbackException(300, "该反馈已废弃，无法回复");
        }
        FeedbackReply r = insertReply(dto.getFeedbackId(), userId, dto.getContent());
        // 异步通知管理员
        // 用户回复通知管理员：notice_type 固定 feedback（文档 §4.4 用户回复行）
        noticeService.notifyUserReply(f.getId(), f.getTitle(), "feedback");
        return r;
    }

    // ==================== 管理端 ====================

    /** 分页（筛选 status/type/keyword/startDate/endDate） */
    public Page<Feedback> page(int pageNum, int pageSize, String status, String type,
                               String keyword, String startDate, String endDate) {
        Page<Feedback> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Feedback> qw = new LambdaQueryWrapper<>();
        if (status != null && !status.isBlank()) {
            qw.eq(Feedback::getStatus, status);
        }
        if (type != null && !type.isBlank()) {
            qw.eq(Feedback::getType, type);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(Feedback::getTitle, keyword).or().like(Feedback::getContent, keyword));
        }
        if (startDate != null && !startDate.isBlank()) {
            qw.ge(Feedback::getCreateTime, startDate);
        }
        if (endDate != null && !endDate.isBlank()) {
            qw.le(Feedback::getCreateTime, endDate + " 23:59:59");
        }
        qw.isNull(Feedback::getDeleteTime);
        qw.orderByDesc(Feedback::getCreateTime);
        Page<Feedback> result = feedbackMapper.selectPage(page, qw);
        // 批量填充提交人用户名/邮箱（JOIN sys_user）
        fillUserInfo(result.getRecords());
        return result;
    }

    /** 为反馈列表批量填充提交人 username/email（JOIN sys_user） */
    private void fillUserInfo(List<Feedback> list) {
        if (list == null || list.isEmpty()) {
            return;
        }
        Set<Long> userIds = list.stream()
                .map(Feedback::getUserId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (userIds.isEmpty()) {
            return;
        }
        Map<Long, User> userMap = userMapper.selectBatchIds(new ArrayList<>(userIds)).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        list.forEach(f -> {
            User u = userMap.get(f.getUserId());
            if (u != null) {
                f.setUsername(u.getUsername());
                f.setEmail(u.getEmail());
            }
        });
    }

    /** 管理端详情 */
    public Feedback adminDetail(Long id) {
        Feedback f = feedbackMapper.selectById(id);
        if (f == null || f.getDeleteTime() != null) {
            throw new FeedbackException(300, "反馈不存在");
        }
        return f;
    }

    /** 状态流转（校验合法流转，触发通知） */
    @Transactional
    public void changeStatus(Long id, StatusDto dto) {
        Feedback f = adminDetail(id);
        String from = f.getStatus();
        String to = dto.getStatus();
        if (to == null || !STATUS_TRANSITIONS.containsKey(to)) {
            throw new FeedbackException(300, "非法的目标状态: " + to);
        }
        List<String> allowed = STATUS_TRANSITIONS.get(from);
        if (!allowed.contains(to)) {
            throw new FeedbackException(300, "不允许从「" + STATUS_NAMES.getOrDefault(from, from)
                    + "」流转到「" + STATUS_NAMES.getOrDefault(to, to) + "」");
        }
        f.setStatus(to);
        f.setUpdateTime(LocalDateTime.now());
        feedbackMapper.updateById(f);

        // 通知规则：仅「已发布」「已废弃」时通知提交人；received/resolved 等中间状态不打扰用户
        String statusName = STATUS_NAMES.getOrDefault(to, to);
        String noticeType = toNoticeType(f.getType());
        if (Feedback.STATUS_PUBLISHED.equals(to)) {
            noticeService.notifyStatusChange(f.getId(), f.getUserId(), f.getTitle(), noticeType, statusName);
            // 发布且公开时，另发「已公开发布」通知
            if (Boolean.TRUE.equals(f.getIsPublic())) {
                noticeService.notifyPublished(f.getId(), f.getUserId(), f.getTitle(), noticeType);
            }
        } else if (Feedback.STATUS_DEPRECATED.equals(to)) {
            noticeService.notifyStatusChange(f.getId(), f.getUserId(), f.getTitle(), noticeType, statusName);
        }
    }

    /** 公开切换 */
    @Transactional
    public void changePublic(Long id, PublicDto dto) {
        Feedback f = adminDetail(id);
        boolean target = dto.getIsPublic() != null && dto.getIsPublic();
        f.setIsPublic(target);
        f.setUpdateTime(LocalDateTime.now());
        feedbackMapper.updateById(f);
        // 切为公开且已发布时，通知提交人
        if (target && Feedback.STATUS_PUBLISHED.equals(f.getStatus())) {
            noticeService.notifyPublished(f.getId(), f.getUserId(), f.getTitle(), toNoticeType(f.getType()));
        }
    }

    /** 软删 */
    public void softDelete(Long id) {
        Feedback f = adminDetail(id);
        f.setDeleteTime(LocalDateTime.now());
        f.setUpdateTime(LocalDateTime.now());
        feedbackMapper.updateById(f);
    }

    /** 管理端回复（同时通知提交人） */
    @Transactional
    public FeedbackReply adminReply(Long adminId, ReplyDto dto) {
        Feedback f = adminDetail(dto.getFeedbackId());
        if (Feedback.STATUS_DEPRECATED.equals(f.getStatus())) {
            throw new FeedbackException(300, "该反馈已废弃，无法回复");
        }
        FeedbackReply r = insertReply(dto.getFeedbackId(), adminId, dto.getContent());
        // 异步通知提交人
        noticeService.notifyAdminReply(f.getId(), f.getUserId(), f.getTitle(), toNoticeType(f.getType()));
        return r;
    }

    /** 统计看板 */
    public Map<String, Object> stat() {
        Map<String, Object> result = new LinkedHashMap<>();
        long total = countByWrapper(w -> w.isNull(Feedback::getDeleteTime));
        result.put("total", total);
        result.put("pending", countByWrapper(w -> w.isNull(Feedback::getDeleteTime).eq(Feedback::getStatus, Feedback.STATUS_PENDING)));
        result.put("received", countByWrapper(w -> w.isNull(Feedback::getDeleteTime).eq(Feedback::getStatus, Feedback.STATUS_RECEIVED)));
        result.put("resolved", countByWrapper(w -> w.isNull(Feedback::getDeleteTime).eq(Feedback::getStatus, Feedback.STATUS_RESOLVED)));
        result.put("published", countByWrapper(w -> w.isNull(Feedback::getDeleteTime).eq(Feedback::getStatus, Feedback.STATUS_PUBLISHED)));
        result.put("deprecated", countByWrapper(w -> w.isNull(Feedback::getDeleteTime).eq(Feedback::getStatus, Feedback.STATUS_DEPRECATED)));
        // 今日新增
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        result.put("todayNew", countByWrapper(w -> w.isNull(Feedback::getDeleteTime).ge(Feedback::getCreateTime, todayStart)));
        // 未回复：无任何管理员回复的未完结反馈数
        result.put("unreplied", countUnreplied());

        // 按类型
        List<Map<String, Object>> byType = new ArrayList<>();
        for (String t : Arrays.asList(Feedback.TYPE_ISSUE, Feedback.TYPE_REQUEST)) {
            Map<String, Object> m = new HashMap<>();
            m.put("type", t);
            m.put("count", countByWrapper(w -> w.isNull(Feedback::getDeleteTime).eq(Feedback::getType, t)));
            byType.add(m);
        }
        result.put("byType", byType);

        // 近7天
        List<Map<String, Object>> last7Days = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MM-dd");
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now().minusDays(i);
            LocalDateTime start = day.atStartOfDay();
            LocalDateTime end = day.plusDays(1).atStartOfDay();
            long count = countByWrapper(w -> w.isNull(Feedback::getDeleteTime)
                    .ge(Feedback::getCreateTime, start).lt(Feedback::getCreateTime, end));
            Map<String, Object> m = new HashMap<>();
            m.put("date", day.format(fmt));
            m.put("count", count);
            last7Days.add(m);
        }
        result.put("last7Days", last7Days);
        return result;
    }

    // ==================== 私有工具 ====================

    private List<FeedbackReply> queryReplies(Long feedbackId) {
        List<FeedbackReply> replies = replyMapper.selectList(new LambdaQueryWrapper<FeedbackReply>()
                .eq(FeedbackReply::getFeedbackId, feedbackId)
                .orderByAsc(FeedbackReply::getReplyTime));
        if (replies.isEmpty()) {
            return replies;
        }
        Set<Long> userIds = replies.stream().map(FeedbackReply::getUserId).collect(Collectors.toSet());
        Map<Long, User> userMap = userMapper.selectBatchIds(new ArrayList<>(userIds)).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        replies.forEach(r -> {
            User u = userMap.get(r.getUserId());
            if (u != null) {
                r.setNickname(u.getNickname() != null ? u.getNickname() : u.getUsername());
                r.setUserType(u.getUserType());
            }
        });
        return replies;
    }

    private FeedbackReply insertReply(Long feedbackId, Long userId, String content) {
        FeedbackReply r = new FeedbackReply();
        r.setFeedbackId(feedbackId);
        r.setUserId(userId);
        r.setContent(content);
        r.setReplyTime(LocalDateTime.now());
        replyMapper.insert(r);
        return r;
    }

    /** 未回复统计：无管理员回复的未完结（非 published/deprecated）反馈数 */
    private long countUnreplied() {
        List<Feedback> pending = feedbackMapper.selectList(new LambdaQueryWrapper<Feedback>()
                .isNull(Feedback::getDeleteTime)
                .notIn(Feedback::getStatus, List.of(Feedback.STATUS_PUBLISHED, Feedback.STATUS_DEPRECATED)));
        if (pending.isEmpty()) {
            return 0;
        }
        // 获取所有有回复的反馈ID（任意回复都算"已联系"——简化：只要有人回复过即不算未回复）
        List<Long> feedbackIds = pending.stream().map(Feedback::getId).collect(Collectors.toList());
        Set<Long> repliedIds = replyMapper.selectList(new LambdaQueryWrapper<FeedbackReply>()
                        .in(FeedbackReply::getFeedbackId, feedbackIds))
                .stream().map(FeedbackReply::getFeedbackId).collect(Collectors.toSet());
        return pending.stream().filter(f -> !repliedIds.contains(f.getId())).count();
    }

    private long countByWrapper(java.util.function.Consumer<LambdaQueryWrapper<Feedback>> customizer) {
        LambdaQueryWrapper<Feedback> qw = new LambdaQueryWrapper<>();
        customizer.accept(qw);
        return feedbackMapper.selectCount(qw);
    }

    private String normalizeType(String type) {
        if (type == null) {
            throw new FeedbackException(320, "类型不能为空");
        }
        String t = type.trim().toLowerCase();
        if (!t.equals(Feedback.TYPE_ISSUE) && !t.equals(Feedback.TYPE_REQUEST)) {
            throw new FeedbackException(320, "非法类型: " + type);
        }
        return t;
    }

    /**
     * 反馈类型 → 通知类型（文档 §4.4：issue→feedback / request→request）
     */
    private String toNoticeType(String feedbackType) {
        return Feedback.TYPE_ISSUE.equals(feedbackType) ? "feedback" : "request";
    }
}
