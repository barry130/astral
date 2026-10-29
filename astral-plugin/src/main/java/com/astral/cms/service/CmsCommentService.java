package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.common.CmsVisibility;
import com.astral.cms.dto.CommentSubmitDto;
import com.astral.cms.entity.CmsComment;
import com.astral.cms.entity.CmsPage;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.mapper.CmsCommentMapper;
import com.astral.cms.mapper.CmsPageMapper;
import com.astral.cms.mapper.CmsPostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CMS 评论服务（匿名提交 + 审核制）
 */
@Slf4j
@Service
public class CmsCommentService {

    /** 同 IP 限流窗口（秒），来自 astral.plugins.cms.rate-limit-window-seconds */
    @Value("${astral.plugins.cms.rate-limit-window-seconds:600}")
    private long throttleWindowSeconds;
    /** 同 IP 窗口内最多提交条数，来自 astral.plugins.cms.rate-limit-count */
    @Value("${astral.plugins.cms.rate-limit-count:5}")
    private int throttleMax;

    /** IP → 提交时间戳列表（内存限流） */
    private final Map<String, List<Long>> submitLog = new ConcurrentHashMap<>();

    @Resource
    private CmsCommentMapper commentMapper;
    @Resource
    private CmsPostMapper postMapper;
    @Resource
    private CmsPageMapper pageMapper;
    @Resource
    private CmsSettingService settingService;

    // ==================== 前台公开 ====================

    /**
     * 公开评论分页（仅已通过审核；不返回 email/ip/userAgent）
     */
    public Page<CmsComment> publicPage(String targetType, Long targetId, int pageNum, int pageSize) {
        String type = validateTargetType(targetType);
        requirePublishedTarget(type, targetId);
        Page<CmsComment> page = commentMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 50)),
                new LambdaQueryWrapper<CmsComment>()
                        .eq(CmsComment::getTargetType, type)
                        .eq(CmsComment::getTargetId, targetId)
                        .eq(CmsComment::getStatus, CmsComment.STATUS_APPROVED)
                        .orderByAsc(CmsComment::getCreateTime));
        page.getRecords().forEach(this::stripPrivateFields);
        return page;
    }

    /**
     * 匿名提交评论（限流 + 开关 + 目标校验）
     *
     * @return 已脱敏的评论（含 id/status）
     */
    public CmsComment submit(CommentSubmitDto dto, String ip, String userAgent) {
        if (!settingService.boolValue(CmsSettingService.KEY_COMMENT_ENABLED)) {
            throw new CmsException("评论功能已关闭");
        }
        String targetType = validateTargetType(dto.getTargetType());
        requirePublishedTarget(targetType, dto.getTargetId());
        checkThrottle(ip);
        CmsComment comment = new CmsComment();
        comment.setTargetType(targetType);
        comment.setTargetId(dto.getTargetId());
        comment.setNickname(dto.getNickname().trim());
        comment.setEmail(blankToNull(dto.getEmail()));
        comment.setContent(dto.getContent().trim());
        boolean needReview = settingService.boolValue(CmsSettingService.KEY_COMMENT_NEED_REVIEW);
        comment.setStatus(needReview ? CmsComment.STATUS_PENDING : CmsComment.STATUS_APPROVED);
        comment.setIp(ip);
        comment.setUserAgent(userAgent == null ? null
                : userAgent.substring(0, Math.min(userAgent.length(), 256)));
        commentMapper.insert(comment);
        stripPrivateFields(comment);
        return comment;
    }

    // ==================== 管理端 ====================

    public Page<CmsComment> adminPage(int pageNum, int pageSize, String status,
                                      String targetType, String keyword) {
        LambdaQueryWrapper<CmsComment> lw = new LambdaQueryWrapper<>();
        if (notBlank(status)) {
            lw.eq(CmsComment::getStatus, status.trim());
        }
        if (notBlank(targetType)) {
            lw.eq(CmsComment::getTargetType, targetType.trim());
        }
        if (notBlank(keyword)) {
            String kw = keyword.trim();
            lw.and(w -> w.like(CmsComment::getNickname, kw).or().like(CmsComment::getContent, kw));
        }
        lw.orderByDesc(CmsComment::getCreateTime);
        Page<CmsComment> page = commentMapper.selectPage(
                new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100)), lw);
        fillTargetTitles(page.getRecords());
        return page;
    }

    /**
     * 审核评论：approved / rejected
     */
    public void audit(Long id, String status) {
        getByIdNotNull(id);
        if (!CmsComment.STATUS_APPROVED.equals(status) && !CmsComment.STATUS_REJECTED.equals(status)) {
            throw new CmsException("审核结果仅支持 approved / rejected");
        }
        commentMapper.update(null, new LambdaUpdateWrapper<CmsComment>()
                .eq(CmsComment::getId, id)
                .set(CmsComment::getStatus, status));
    }

    public void delete(Long id) {
        getByIdNotNull(id);
        commentMapper.deleteById(id);
    }

    // ==================== 私有辅助 ====================

    private void checkThrottle(String ip) {
        String key = ip == null || ip.isBlank() ? "unknown" : ip;
        long now = System.currentTimeMillis();
        // 粗粒度防内存膨胀：键过多时清理过期桶
        if (submitLog.size() > 1000) {
            submitLog.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    return e.getValue().stream().allMatch(t -> now - t > throttleWindowSeconds * 1000L);
                }
            });
        }
        List<Long> list = submitLog.computeIfAbsent(key, k -> new ArrayList<>());
        synchronized (list) {
            list.removeIf(t -> now - t > throttleWindowSeconds * 1000L);
            if (list.size() >= throttleMax) {
                log.warn("[CmsPlugin] 评论限流触发 ip={}", key);
                throw new CmsException("评论太频繁啦，请稍后再试");
            }
            list.add(now);
        }
    }

    /**
     * 校验评论目标存在且对当前访客可见（私密内容仅登录的管理员可评论）
     */
    private void requirePublishedTarget(String targetType, Long targetId) {
        if (CmsComment.TARGET_POST.equals(targetType)) {
            CmsPost post = postMapper.selectById(targetId);
            if (post == null || post.getDeleteTime() != null
                    || !CmsVisibility.isVisible(post.getStatus())) {
                throw new CmsException("文章不存在或未发布，无法评论");
            }
        } else {
            CmsPage page = pageMapper.selectById(targetId);
            if (page == null || page.getDeleteTime() != null
                    || !CmsVisibility.isVisible(page.getStatus())) {
                throw new CmsException("页面不存在或未发布，无法评论");
            }
        }
    }

    private void fillTargetTitles(List<CmsComment> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        Set<Long> postIds = new HashSet<>();
        Set<Long> pageIds = new HashSet<>();
        for (CmsComment c : comments) {
            if (CmsComment.TARGET_POST.equals(c.getTargetType())) {
                postIds.add(c.getTargetId());
            } else {
                pageIds.add(c.getTargetId());
            }
        }
        Map<Long, String> titles = new HashMap<>();
        if (!postIds.isEmpty()) {
            postMapper.selectBatchIds(postIds).forEach(p -> titles.put(p.getId(), p.getTitle()));
        }
        if (!pageIds.isEmpty()) {
            pageMapper.selectBatchIds(pageIds).forEach(p -> titles.put(p.getId(), p.getTitle()));
        }
        for (CmsComment c : comments) {
            String title = titles.get(c.getTargetId());
            c.setTargetTitle(title == null ? "（目标已删除）" : title);
        }
    }

    private void stripPrivateFields(CmsComment c) {
        c.setEmail(null);
        c.setIp(null);
        c.setUserAgent(null);
    }

    private String validateTargetType(String targetType) {
        if (CmsComment.TARGET_POST.equals(targetType) || CmsComment.TARGET_PAGE.equals(targetType)) {
            return targetType;
        }
        throw new CmsException("不支持的评论目标类型：" + targetType);
    }

    private CmsComment getByIdNotNull(Long id) {
        CmsComment comment = commentMapper.selectById(id);
        if (comment == null) {
            throw new CmsException("评论不存在");
        }
        return comment;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
