package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.common.CmsVisibility;
import com.astral.cms.dto.TagSaveDto;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.entity.CmsPostTag;
import com.astral.cms.entity.CmsTag;
import com.astral.cms.mapper.CmsPostMapper;
import com.astral.cms.mapper.CmsPostTagMapper;
import com.astral.cms.mapper.CmsTagMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CMS 标签服务
 */
@Service
public class CmsTagService {

    @Resource
    private CmsTagMapper tagMapper;
    @Resource
    private CmsPostTagMapper postTagMapper;
    @Resource
    private CmsPostMapper postMapper;

    /**
     * 管理端标签列表（含关联已发布文章数）
     */
    public List<CmsTag> adminList() {
        List<CmsTag> tags = tagMapper.selectList(new LambdaQueryWrapper<CmsTag>()
                .orderByAsc(CmsTag::getId));
        fillPostCounts(tags);
        return tags;
    }

    /**
     * 前台标签列表（公开，含关联已发布文章数）
     */
    public List<CmsTag> publicList() {
        return adminList();
    }

    public Long create(TagSaveDto dto) {
        checkNameUnique(dto.getName(), null);
        CmsTag tag = new CmsTag();
        tag.setName(dto.getName().trim());
        tag.setSlug(blankToNull(dto.getSlug()));
        tagMapper.insert(tag);
        return tag.getId();
    }

    public void update(Long id, TagSaveDto dto) {
        getByIdNotNull(id);
        checkNameUnique(dto.getName(), id);
        LambdaUpdateWrapper<CmsTag> uw = new LambdaUpdateWrapper<CmsTag>()
                .eq(CmsTag::getId, id)
                .set(CmsTag::getName, dto.getName().trim())
                .set(CmsTag::getSlug, blankToNull(dto.getSlug()));
        tagMapper.update(null, uw);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getByIdNotNull(id);
        postTagMapper.delete(new LambdaQueryWrapper<CmsPostTag>().eq(CmsPostTag::getTagId, id));
        tagMapper.deleteById(id);
    }

    // ==================== 私有辅助 ====================

    private void fillPostCounts(List<CmsTag> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        Set<Long> tagIds = new HashSet<>();
        tags.forEach(t -> tagIds.add(t.getId()));
        Set<Long> publishedPostIds = new HashSet<>();
        // 匿名访客只算已发布；登录的管理员把私密文章一起算进来
        LambdaQueryWrapper<CmsPost> lw = new LambdaQueryWrapper<CmsPost>()
                .select(CmsPost::getId)
                .isNull(CmsPost::getDeleteTime);
        if (CmsVisibility.canViewPrivate()) {
            lw.in(CmsPost::getStatus, CmsPost.STATUS_PUBLISHED, CmsPost.STATUS_PRIVATE);
        } else {
            lw.eq(CmsPost::getStatus, CmsPost.STATUS_PUBLISHED);
        }
        postMapper.selectList(lw).forEach(p -> {
            if (p != null && p.getId() != null) {
                publishedPostIds.add(p.getId());
            }
        });
        Map<Long, Long> counts = new HashMap<>();
        if (!publishedPostIds.isEmpty()) {
            postTagMapper.selectList(new LambdaQueryWrapper<CmsPostTag>()
                            .in(CmsPostTag::getTagId, tagIds))
                    .forEach(pt -> {
                        if (publishedPostIds.contains(pt.getPostId())) {
                            counts.merge(pt.getTagId(), 1L, Long::sum);
                        }
                    });
        }
        tags.forEach(t -> t.setPostCount(counts.getOrDefault(t.getId(), 0L)));
    }

    private void checkNameUnique(String name, Long excludeId) {
        if (name == null || name.isBlank()) {
            throw new CmsException("标签名不能为空");
        }
        LambdaQueryWrapper<CmsTag> lw = new LambdaQueryWrapper<CmsTag>()
                .eq(CmsTag::getName, name.trim());
        if (excludeId != null) {
            lw.ne(CmsTag::getId, excludeId);
        }
        if (tagMapper.selectCount(lw) > 0) {
            throw new CmsException("标签已存在：" + name.trim());
        }
    }

    private CmsTag getByIdNotNull(Long id) {
        CmsTag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw new CmsException("标签不存在");
        }
        return tag;
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
