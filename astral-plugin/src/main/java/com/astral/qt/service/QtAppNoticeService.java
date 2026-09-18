package com.astral.qt.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.astral.qt.entity.QtAppNotice;
import com.astral.qt.entity.QtAppNoticeRead;
import com.astral.qt.mapper.QtAppNoticeMapper;
import com.astral.qt.mapper.QtAppNoticeReadMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 轻听 App 公告服务
 * <p>
 * 展示渠道位掩码（type 字段按位叠加）：
 * 1=开屏弹窗, 2=首页顶部通告栏, 4=消息中心。
 * </p>
 * <p><b>已废弃</b>：统一通知迁移至 astral-plugin 的 feedback 分类（sys_notice），此服务保留兼容，不再被 App 调用。</p>
 *
 * @deprecated 使用反馈插件的统一通知服务
 */
@Deprecated
@Slf4j
@Service
public class QtAppNoticeService {

    /** 展示渠道编码 */
    public static final long CHANNEL_SPLASH = 1L;
    public static final long CHANNEL_NOTICE_BAR = 2L;
    public static final long CHANNEL_MESSAGE_CENTER = 4L;

    @Resource
    private QtAppNoticeMapper qtAppNoticeMapper;

    @Resource
    private QtAppNoticeReadMapper qtAppNoticeReadMapper;

    /** 判断公告是否包含指定渠道（channel 为 2 的幂） */
    public static boolean hasChannel(Long type, long channel) {
        if (type == null) return false;
        return (type & channel) == channel;
    }

    /**
     * 取当前生效中的公告（供 App 各展示位使用）。
     * 过滤条件：启用 + 生效时间窗口 + APP 版本区间 + 登录人群（audience）。
     * 排序：置顶优先，其次创建时间倒序。
     *
     * @param appVersion 客户端版本号（如 300 对应 3.0.0），可为空
     * @param loggedIn   调用方是否已登录（用于 audience 过滤）
     */
    public List<QtAppNotice> listActive(String appVersion, boolean loggedIn) {
        LambdaQueryWrapper<QtAppNotice> qw = new LambdaQueryWrapper<>();
        qw.eq(QtAppNotice::getIsShow, 1L);
        qw.orderByDesc(QtAppNotice::getIsTop);
        qw.orderByDesc(QtAppNotice::getCreateTime);
        List<QtAppNotice> all = qtAppNoticeMapper.selectList(qw);

        LocalDateTime now = LocalDateTime.now();
        return all.stream()
                .filter(n -> inEffectiveWindow(n, now))
                .filter(n -> inVersionRange(n, appVersion))
                .filter(n -> audienceMatches(n.getAudience(), loggedIn))
                .collect(Collectors.toList());
    }

    /** 取消息中心列表（已过滤渠道含「消息中心」且对登录用户可见的公告），并附已读状态 */
    public List<QtAppNotice> listMessageCenter(Long userId) {
        List<QtAppNotice> active = listActive(null, true);
        List<QtAppNotice> center = active.stream()
                .filter(n -> hasChannel(n.getType(), CHANNEL_MESSAGE_CENTER))
                .collect(Collectors.toList());

        Set<Long> readIds = readNoticeIds(userId, center.stream().map(QtAppNotice::getId).collect(Collectors.toList()));
        center.forEach(n -> n.setRead(readIds.contains(n.getId())));
        return center;
    }

    /**
     * 按登录人群过滤
     * <ul>
     *   <li>ALL：登录/未登录均展示</li>
     *   <li>LOGGED_IN：仅登录用户</li>
     *   <li>NOT_LOGGED_IN：仅未登录（游客）</li>
     * </ul>
     */
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

    /** 消息中心未读数 */
    public long countUnread(Long userId) {
        List<QtAppNotice> center = listMessageCenter(userId);
        return center.stream().filter(n -> !Boolean.TRUE.equals(n.getRead())).count();
    }

    /** 标记已读（单条或批量），幂等 */
    public void markRead(Long userId, List<Long> noticeIds) {
        if (userId == null || noticeIds == null || noticeIds.isEmpty()) return;
        Set<Long> readIds = readNoticeIds(userId, noticeIds);
        LocalDateTime now = LocalDateTime.now();
        for (Long id : noticeIds) {
            if (readIds.contains(id)) continue;
            QtAppNoticeRead r = new QtAppNoticeRead();
            r.setNoticeId(id);
            r.setUserId(userId);
            r.setReadTime(now);
            try {
                qtAppNoticeReadMapper.insert(r);
            } catch (Exception e) {
                // 并发重复主键时忽略
                log.debug("[QtAppNotice] 已读记录插入忽略: {}", e.getMessage());
            }
        }
    }

    private Set<Long> readNoticeIds(Long userId, List<Long> noticeIds) {
        if (userId == null || noticeIds == null || noticeIds.isEmpty()) return Set.of();
        LambdaQueryWrapper<QtAppNoticeRead> qw = new LambdaQueryWrapper<>();
        qw.eq(QtAppNoticeRead::getUserId, userId);
        qw.in(QtAppNoticeRead::getNoticeId, noticeIds);
        return qtAppNoticeReadMapper.selectList(qw).stream()
                .map(QtAppNoticeRead::getNoticeId)
                .collect(Collectors.toSet());
    }

    private boolean inEffectiveWindow(QtAppNotice n, LocalDateTime now) {
        if (n.getEffectiveStart() != null && now.isBefore(n.getEffectiveStart())) return false;
        if (n.getEffectiveEnd() != null && now.isAfter(n.getEffectiveEnd())) return false;
        return true;
    }

    private boolean inVersionRange(QtAppNotice n, String appVersion) {
        if (appVersion == null || appVersion.isBlank()) return true;
        Long current;
        try {
            current = Long.parseLong(appVersion.trim());
        } catch (NumberFormatException e) {
            // 非数字版本号无法比较，视为不限制
            return true;
        }
        Long min = n.getVersionMin();
        Long max = n.getVersionMax();
        if (min != null && current < min) return false;
        if (max != null && current > max) return false;
        return true;
    }
}
