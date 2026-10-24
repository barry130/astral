package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.entity.CmsPage;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.entity.CmsShare;
import com.astral.cms.mapper.CmsPageMapper;
import com.astral.cms.mapper.CmsPostMapper;
import com.astral.cms.mapper.CmsShareMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CMS 分享链接服务
 * <p>为单篇文章或单个页面生成带随机 token 的只读访问链接：
 * 可随时撤销（删除记录）、可设过期时间。分享链接的价值在于让「私密」内容
 * 以只读方式分享给未登录访客，因此通过分享 token 访问时不受 status=private 限制。</p>
 */
@Service
public class CmsShareService {

    /** 分享 token 字节数（16 字节 → 22 位 base64url，碰撞概率可忽略） */
    private static final int TOKEN_BYTES = 16;

    /** token 生成的重试次数（唯一索引冲突时重来） */
    private static final int TOKEN_RETRY = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Resource
    private CmsShareMapper shareMapper;
    @Resource
    private CmsPostMapper postMapper;
    @Resource
    private CmsPageMapper pageMapper;
    @Resource
    private CmsSettingService settingService;

    /**
     * 目标内容的全部分享链接（管理端）
     */
    public List<CmsShare> listByTarget(String targetType, Long targetId) {
        List<CmsShare> shares = shareMapper.selectList(new LambdaQueryWrapper<CmsShare>()
                .eq(CmsShare::getTargetType, targetType)
                .eq(CmsShare::getTargetId, targetId)
                .orderByDesc(CmsShare::getId));
        fillTargetTitles(shares);
        return shares;
    }

    /**
     * 全部分享链接（管理端「分享管理」列表）
     */
    public List<CmsShare> listAll() {
        List<CmsShare> shares = shareMapper.selectList(new LambdaQueryWrapper<CmsShare>()
                .orderByDesc(CmsShare::getId));
        fillTargetTitles(shares);
        return shares;
    }

    /**
     * 创建分享链接
     *
     * @param expireAt 过期时间（NULL=永久）
     */
    public CmsShare create(String targetType, Long targetId, LocalDateTime expireAt,
                           String remark, Long createBy) {
        String type = validateTargetType(targetType);
        requireTarget(type, targetId);
        if (expireAt != null && expireAt.isBefore(LocalDateTime.now())) {
            throw new CmsException("过期时间不能早于当前时间");
        }
        CmsShare share = new CmsShare();
        share.setTargetType(type);
        share.setTargetId(targetId);
        share.setToken(generateUniqueToken());
        share.setExpireAt(expireAt);
        share.setRemark(blankToNull(remark));
        share.setCreateBy(createBy);
        shareMapper.insert(share);
        fillTargetTitles(List.of(share));
        return share;
    }

    /**
     * 撤销分享链接（物理删除，立即失效）
     */
    public void revoke(Long id) {
        if (shareMapper.selectById(id) == null) {
            throw new CmsException("分享链接不存在或已撤销");
        }
        shareMapper.deleteById(id);
    }

    /**
     * 撤销某个内容的全部分享链接
     */
    public int revokeByTarget(String targetType, Long targetId) {
        String type = validateTargetType(targetType);
        return shareMapper.delete(new LambdaQueryWrapper<CmsShare>()
                .eq(CmsShare::getTargetType, type)
                .eq(CmsShare::getTargetId, targetId));
    }

    /**
     * 按 token 取有效分享（前台）——不存在或已过期均返回 null，由调用方转 404
     */
    public CmsShare findValidByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        CmsShare share = shareMapper.selectOne(new LambdaQueryWrapper<CmsShare>()
                .eq(CmsShare::getToken, token.trim())
                .last("LIMIT 1"));
        if (share == null) {
            return null;
        }
        if (share.getExpireAt() != null && share.getExpireAt().isBefore(LocalDateTime.now())) {
            return null;
        }
        return share;
    }

    // ==================== 私有辅助 ====================

    private String validateTargetType(String targetType) {
        if (CmsShare.TARGET_POST.equals(targetType) || CmsShare.TARGET_PAGE.equals(targetType)) {
            return targetType;
        }
        throw new CmsException("不支持的分享目标类型：" + targetType);
    }

    private void requireTarget(String targetType, Long targetId) {
        if (targetId == null) {
            throw new CmsException("分享目标不能为空");
        }
        if (CmsShare.TARGET_POST.equals(targetType)) {
            CmsPost post = postMapper.selectById(targetId);
            if (post == null || post.getDeleteTime() != null) {
                throw new CmsException("文章不存在或已删除");
            }
        } else {
            CmsPage page = pageMapper.selectById(targetId);
            if (page == null || page.getDeleteTime() != null) {
                throw new CmsException("页面不存在或已删除");
            }
        }
    }

    /**
     * 生成唯一 token：base64url 无填充，遇到唯一索引冲突则重试
     */
    private String generateUniqueToken() {
        for (int i = 0; i < TOKEN_RETRY; i++) {
            byte[] bytes = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            if (shareMapper.selectCount(new LambdaQueryWrapper<CmsShare>()
                    .eq(CmsShare::getToken, token)) == 0) {
                return token;
            }
        }
        throw new CmsException("生成分享令牌失败，请重试");
    }

    private void fillTargetTitles(List<CmsShare> shares) {
        if (shares == null || shares.isEmpty()) {
            return;
        }
        List<Long> postIds = new ArrayList<>();
        List<Long> pageIds = new ArrayList<>();
        for (CmsShare s : shares) {
            if (CmsShare.TARGET_POST.equals(s.getTargetType())) {
                postIds.add(s.getTargetId());
            } else {
                pageIds.add(s.getTargetId());
            }
            s.setExpired(s.getExpireAt() != null && s.getExpireAt().isBefore(LocalDateTime.now()));
        }
        Map<Long, String> titles = new HashMap<>();
        if (!postIds.isEmpty()) {
            postMapper.selectBatchIds(postIds.stream().distinct().toList())
                    .forEach(p -> titles.put(p.getId(), p.getTitle()));
        }
        if (!pageIds.isEmpty()) {
            pageMapper.selectBatchIds(pageIds.stream().distinct().toList())
                    .forEach(p -> titles.put(p.getId(), p.getTitle()));
        }
        String base = shareBaseUrl();
        shares.forEach(s -> {
            s.setTargetTitle(titles.get(s.getTargetId()));
            s.setUrl(base.isEmpty() ? null : base + "/share/" + s.getToken());
        });
    }

    /**
     * 分享链接的站点根地址：取站点设置 blog_url，未配置时返回空串（前端回退同源相对路径）
     */
    private String shareBaseUrl() {
        String url = settingService.getAll().get(CmsSettingService.KEY_BLOG_URL);
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.trim().replaceAll("/+$", "");
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
