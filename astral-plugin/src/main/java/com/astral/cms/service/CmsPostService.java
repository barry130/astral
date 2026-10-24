package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.common.CmsVisibility;
import com.astral.cms.dto.PostSaveDto;
import com.astral.cms.entity.CmsCategory;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.entity.CmsPostCategory;
import com.astral.cms.entity.CmsPostTag;
import com.astral.cms.entity.CmsTag;
import com.astral.cms.mapper.CmsCategoryMapper;
import com.astral.cms.mapper.CmsPostCategoryMapper;
import com.astral.cms.mapper.CmsPostMapper;
import com.astral.cms.mapper.CmsPostTagMapper;
import com.astral.cms.mapper.CmsTagMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CMS 文章服务
 * <p>分类：一篇文章可属于多个分类（cms_post_category 关联表），
 * cms_post.category_id 作为主分类冗余列保留，取提交列表的第一项。</p>
 */
@Service
public class CmsPostService {

    @Resource
    private CmsPostMapper postMapper;
    @Resource
    private CmsCategoryMapper categoryMapper;
    @Resource
    private CmsTagMapper tagMapper;
    @Resource
    private CmsPostTagMapper postTagMapper;
    @Resource
    private CmsPostCategoryMapper postCategoryMapper;

    // ==================== 管理端 ====================

    public Page<CmsPost> adminPage(int pageNum, int pageSize, String status, Long categoryId,
                                   Long tagId, String keyword) {
        LambdaQueryWrapper<CmsPost> lw = new LambdaQueryWrapper<CmsPost>()
                .isNull(CmsPost::getDeleteTime);
        if (notBlank(status)) {
            lw.eq(CmsPost::getStatus, status.trim());
        }
        if (categoryId != null) {
            appendCategoryFilter(lw, categoryId);
        }
        if (tagId != null) {
            List<Long> postIds = postTagMapper.selectList(new LambdaQueryWrapper<CmsPostTag>()
                            .eq(CmsPostTag::getTagId, tagId))
                    .stream().map(CmsPostTag::getPostId).distinct().toList();
            if (postIds.isEmpty()) {
                return new Page<>(pageNum, Math.max(pageSize, 1));
            }
            lw.in(CmsPost::getId, postIds);
        }
        if (notBlank(keyword)) {
            lw.like(CmsPost::getTitle, keyword.trim());
        }
        lw.orderByDesc(CmsPost::getUpdateTime);
        Page<CmsPost> page = postMapper.selectPage(new Page<>(Math.max(pageNum, 1),
                Math.min(Math.max(pageSize, 1), 100)), lw);
        fillCategoryAndTags(page.getRecords());
        return page;
    }

    public CmsPost adminDetail(Long id) {
        CmsPost post = getByIdNotNull(id);
        fillCategoryAndTags(List.of(post));
        return post;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(PostSaveDto dto, Long authorId) {
        String status = validateStatus(dto.getStatus());
        checkSlugUnique(dto.getSlug(), null);
        List<Long> categoryIds = resolveCategoryIds(dto);
        CmsPost post = new CmsPost();
        post.setTitle(dto.getTitle().trim());
        post.setSlug(blankToNull(dto.getSlug()));
        post.setSummary(blankToNull(dto.getSummary()));
        post.setContent(dto.getContent());
        post.setCover(blankToNull(dto.getCover()));
        post.setCategoryId(primaryCategoryId(categoryIds));
        post.setStatus(status);
        post.setIsTop(Boolean.TRUE.equals(dto.getIsTop()));
        post.setViews(0L);
        post.setAuthorId(authorId);
        post.setPublishedAt(dto.getPublishedAt());
        if (isPubliclyVisible(status) && post.getPublishedAt() == null) {
            post.setPublishedAt(LocalDateTime.now());
        }
        postMapper.insert(post);
        saveCategories(post.getId(), categoryIds);
        saveTags(post.getId(), dto.getTagIds());
        return post.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PostSaveDto dto) {
        CmsPost exist = getByIdNotNull(id);
        String status = validateStatus(dto.getStatus());
        checkSlugUnique(dto.getSlug(), id);
        List<Long> categoryIds = resolveCategoryIds(dto);
        LocalDateTime publishedAt = dto.getPublishedAt();
        if (isPubliclyVisible(status)
                && exist.getPublishedAt() == null && publishedAt == null) {
            publishedAt = LocalDateTime.now();
        }
        LambdaUpdateWrapper<CmsPost> uw = new LambdaUpdateWrapper<CmsPost>()
                .eq(CmsPost::getId, id)
                .set(CmsPost::getTitle, dto.getTitle().trim())
                .set(CmsPost::getSlug, blankToNull(dto.getSlug()))
                .set(CmsPost::getSummary, blankToNull(dto.getSummary()))
                .set(CmsPost::getContent, dto.getContent())
                .set(CmsPost::getCover, blankToNull(dto.getCover()))
                .set(CmsPost::getCategoryId, primaryCategoryId(categoryIds))
                .set(CmsPost::getStatus, status)
                .set(CmsPost::getIsTop, Boolean.TRUE.equals(dto.getIsTop()))
                .set(CmsPost::getPublishedAt, publishedAt);
        postMapper.update(null, uw);
        saveCategories(id, categoryIds);
        saveTags(id, dto.getTagIds());
    }

    public void changeStatus(Long id, String status) {
        CmsPost exist = getByIdNotNull(id);
        String next = validateStatus(status);
        LambdaUpdateWrapper<CmsPost> uw = new LambdaUpdateWrapper<CmsPost>()
                .eq(CmsPost::getId, id)
                .set(CmsPost::getStatus, next);
        if (isPubliclyVisible(next) && exist.getPublishedAt() == null) {
            uw.set(CmsPost::getPublishedAt, LocalDateTime.now());
        }
        postMapper.update(null, uw);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        getByIdNotNull(id);
        LambdaUpdateWrapper<CmsPost> uw = new LambdaUpdateWrapper<CmsPost>()
                .eq(CmsPost::getId, id)
                .set(CmsPost::getDeleteTime, LocalDateTime.now());
        postMapper.update(null, uw);
        postTagMapper.delete(new LambdaQueryWrapper<CmsPostTag>().eq(CmsPostTag::getPostId, id));
        postCategoryMapper.delete(new LambdaQueryWrapper<CmsPostCategory>()
                .eq(CmsPostCategory::getPostId, id));
    }

    // ==================== 前台公开 ====================

    public Page<CmsPost> publicPage(int pageNum, int pageSize, String categorySlug,
                                    String tagSlug, String keyword) {
        LambdaQueryWrapper<CmsPost> lw = new LambdaQueryWrapper<CmsPost>()
                .isNull(CmsPost::getDeleteTime);
        appendVisibilityFilter(lw);
        if (notBlank(categorySlug)) {
            CmsCategory category = resolveCategory(categorySlug.trim());
            if (category == null) {
                return new Page<>(pageNum, Math.max(pageSize, 1));
            }
            appendCategoryFilter(lw, category.getId());
        }
        if (notBlank(tagSlug)) {
            CmsTag tag = resolveTag(tagSlug.trim());
            if (tag == null) {
                return new Page<>(pageNum, Math.max(pageSize, 1));
            }
            List<Long> postIds = postTagMapper.selectList(new LambdaQueryWrapper<CmsPostTag>()
                            .eq(CmsPostTag::getTagId, tag.getId()))
                    .stream().map(CmsPostTag::getPostId).distinct().toList();
            if (postIds.isEmpty()) {
                return new Page<>(pageNum, Math.max(pageSize, 1));
            }
            lw.in(CmsPost::getId, postIds);
        }
        if (notBlank(keyword)) {
            lw.like(CmsPost::getTitle, keyword.trim());
        }
        lw.orderByDesc(CmsPost::getIsTop)
                .orderByDesc(CmsPost::getPublishedAt)
                .orderByDesc(CmsPost::getId);
        Page<CmsPost> page = postMapper.selectPage(new Page<>(Math.max(pageNum, 1),
                Math.min(Math.max(pageSize, 1), 50)), lw);
        page.getRecords().forEach(p -> p.setContent(null));
        fillCategoryAndTags(page.getRecords());
        return page;
    }

    public CmsPost publicDetail(String idOrSlug) {
        CmsPost post;
        if (idOrSlug.matches("\\d+")) {
            post = postMapper.selectById(Long.parseLong(idOrSlug));
        } else {
            post = postMapper.selectOne(new LambdaQueryWrapper<CmsPost>()
                    .eq(CmsPost::getSlug, idOrSlug)
                    .isNull(CmsPost::getDeleteTime)
                    .last("LIMIT 1"));
        }
        if (post == null || post.getDeleteTime() != null
                || !CmsVisibility.isVisible(post.getStatus())) {
            throw new CmsException(404, "文章不存在或未发布");
        }
        postMapper.update(null, new LambdaUpdateWrapper<CmsPost>()
                .eq(CmsPost::getId, post.getId())
                .setSql("views = views + 1"));
        fillCategoryAndTags(List.of(post));
        return post;
    }

    /**
     * 按ID取文章详情（供分享链接使用：跳过状态可见性校验，仅校验未删除）
     * <p>调用方必须先校验分享 token 有效——分享本身就是「临时授权」，
     * 因此这里不再看 status（私密文章也能通过有效分享链接读到）。</p>
     */
    public CmsPost publicDetailById(Long id) {
        CmsPost post = postMapper.selectById(id);
        if (post == null || post.getDeleteTime() != null) {
            throw new CmsException(404, "文章不存在或已删除");
        }
        fillCategoryAndTags(List.of(post));
        return post;
    }

    // ==================== 私有辅助 ====================

    /**
     * 前台可见性过滤：已发布始终可见；私密仅对登录的管理员可见。
     * <p>用 in 一次性表达，避免和外部已有的 or 条件错误扁平化。</p>
     */
    private void appendVisibilityFilter(LambdaQueryWrapper<CmsPost> lw) {
        if (CmsVisibility.canViewPrivate()) {
            lw.in(CmsPost::getStatus, CmsPost.STATUS_PUBLISHED, CmsPost.STATUS_PRIVATE);
        } else {
            lw.eq(CmsPost::getStatus, CmsPost.STATUS_PUBLISHED);
        }
    }

    /**
     * 该状态是否算「内容已就绪」，需要写入首次发布时间（published 与 private 都是）
     */
    private boolean isPubliclyVisible(String status) {
        return CmsPost.STATUS_PUBLISHED.equals(status) || CmsPost.STATUS_PRIVATE.equals(status);
    }

    /**
     * 按分类筛选（包含该分类的文章）
     * <p>关联表优先；同时兼容只有 cms_post.category_id 冗余列、尚无关联行的历史数据。</p>
     */
    private void appendCategoryFilter(LambdaQueryWrapper<CmsPost> lw, Long categoryId) {
        List<Long> postIds = postCategoryMapper.selectList(
                        new LambdaQueryWrapper<CmsPostCategory>()
                                .eq(CmsPostCategory::getCategoryId, categoryId))
                .stream().map(CmsPostCategory::getPostId).distinct().toList();
        if (postIds.isEmpty()) {
            lw.eq(CmsPost::getCategoryId, categoryId);
            return;
        }
        lw.and(w -> w.in(CmsPost::getId, postIds).or().eq(CmsPost::getCategoryId, categoryId));
    }

    /**
     * 归一化提交的分类ID：优先 categoryIds，回退旧的 categoryId（去重、去空、保序）
     */
    private List<Long> resolveCategoryIds(PostSaveDto dto) {
        Set<Long> ordered = new LinkedHashSet<>();
        if (dto.getCategoryIds() != null) {
            dto.getCategoryIds().stream().filter(Objects::nonNull).forEach(ordered::add);
        }
        // 兼容旧前端：只传 categoryId 时视为单分类
        if (ordered.isEmpty() && dto.getCategoryId() != null) {
            ordered.add(dto.getCategoryId());
        }
        List<Long> ids = new ArrayList<>(ordered);
        for (Long categoryId : ids) {
            if (categoryMapper.selectById(categoryId) == null) {
                throw new CmsException("所属分类不存在：" + categoryId);
            }
        }
        return ids;
    }

    private Long primaryCategoryId(List<Long> categoryIds) {
        return (categoryIds == null || categoryIds.isEmpty()) ? null : categoryIds.get(0);
    }

    private void saveCategories(Long postId, List<Long> categoryIds) {
        postCategoryMapper.delete(new LambdaQueryWrapper<CmsPostCategory>()
                .eq(CmsPostCategory::getPostId, postId));
        if (categoryIds == null || categoryIds.isEmpty()) {
            return;
        }
        for (Long categoryId : categoryIds) {
            CmsPostCategory pc = new CmsPostCategory();
            pc.setPostId(postId);
            pc.setCategoryId(categoryId);
            postCategoryMapper.insert(pc);
        }
    }

    private void fillCategoryAndTags(List<CmsPost> posts) {
        if (posts == null || posts.isEmpty()) {
            return;
        }
        List<Long> postIds = new ArrayList<>();
        for (CmsPost p : posts) {
            postIds.add(p.getId());
        }
        // 文章 → 分类ID集合（关联表为准，回退冗余列）
        Map<Long, List<Long>> categoryIdsByPost = new HashMap<>();
        postCategoryMapper.selectList(new LambdaQueryWrapper<CmsPostCategory>()
                        .in(CmsPostCategory::getPostId, postIds))
                .forEach(pc -> categoryIdsByPost
                        .computeIfAbsent(pc.getPostId(), k -> new ArrayList<>())
                        .add(pc.getCategoryId()));
        Set<Long> allCategoryIds = new HashSet<>();
        categoryIdsByPost.values().forEach(allCategoryIds::addAll);
        for (CmsPost p : posts) {
            if (!categoryIdsByPost.containsKey(p.getId()) && p.getCategoryId() != null) {
                categoryIdsByPost.put(p.getId(), List.of(p.getCategoryId()));
                allCategoryIds.add(p.getCategoryId());
            }
        }
        Map<Long, CmsCategory> categoryMap = new HashMap<>();
        if (!allCategoryIds.isEmpty()) {
            categoryMapper.selectBatchIds(allCategoryIds).forEach(c -> categoryMap.put(c.getId(), c));
        }
        // 文章 → 标签
        Map<Long, List<Long>> tagIdsByPost = new HashMap<>();
        Set<Long> allTagIds = new HashSet<>();
        postTagMapper.selectList(new LambdaQueryWrapper<CmsPostTag>()
                        .in(CmsPostTag::getPostId, postIds))
                .forEach(pt -> tagIdsByPost
                        .computeIfAbsent(pt.getPostId(), k -> new ArrayList<>())
                        .add(pt.getTagId()));
        tagIdsByPost.values().forEach(allTagIds::addAll);
        Map<Long, CmsTag> tagMap = new HashMap<>();
        if (!allTagIds.isEmpty()) {
            tagMapper.selectBatchIds(allTagIds).forEach(t -> tagMap.put(t.getId(), t));
        }
        for (CmsPost p : posts) {
            List<Long> ids = categoryIdsByPost.getOrDefault(p.getId(), List.of());
            // 主分类排在最前，保证 categories[0] 与 categoryId 一致
            List<Long> ordered = new ArrayList<>();
            if (p.getCategoryId() != null && ids.contains(p.getCategoryId())) {
                ordered.add(p.getCategoryId());
            }
            for (Long cid : ids) {
                if (!ordered.contains(cid)) {
                    ordered.add(cid);
                }
            }
            List<CmsPost.CategoryRef> refs = new ArrayList<>();
            for (Long cid : ordered) {
                CmsCategory c = categoryMap.get(cid);
                if (c != null) {
                    refs.add(new CmsPost.CategoryRef(c.getId(), c.getName(), c.getSlug()));
                }
            }
            p.setCategories(refs);
            p.setCategoryName(refs.isEmpty() ? null : refs.get(0).getName());
            p.setTags(tagIdsByPost.getOrDefault(p.getId(), List.of()).stream()
                    .map(tagMap::get).filter(Objects::nonNull).toList());
        }
    }

    private void saveTags(Long postId, List<Long> tagIds) {
        postTagMapper.delete(new LambdaQueryWrapper<CmsPostTag>().eq(CmsPostTag::getPostId, postId));
        if (tagIds == null || tagIds.isEmpty()) {
            return;
        }
        List<Long> distinct = tagIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return;
        }
        Set<Long> existIds = tagMapper.selectBatchIds(distinct).stream()
                .map(CmsTag::getId).collect(java.util.stream.Collectors.toSet());
        for (Long tagId : distinct) {
            if (!existIds.contains(tagId)) {
                continue;
            }
            CmsPostTag pt = new CmsPostTag();
            pt.setPostId(postId);
            pt.setTagId(tagId);
            postTagMapper.insert(pt);
        }
    }

    private CmsPost getByIdNotNull(Long id) {
        CmsPost post = postMapper.selectById(id);
        if (post == null || post.getDeleteTime() != null) {
            throw new CmsException("文章不存在或已删除");
        }
        return post;
    }

    private String validateStatus(String status) {
        if (CmsPost.STATUS_DRAFT.equals(status) || CmsPost.STATUS_PUBLISHED.equals(status)
                || CmsPost.STATUS_PRIVATE.equals(status) || CmsPost.STATUS_HIDDEN.equals(status)) {
            return status;
        }
        throw new CmsException("不支持的内容状态：" + status);
    }

    private void checkSlugUnique(String slug, Long excludeId) {
        if (blankToNull(slug) == null) {
            return;
        }
        LambdaQueryWrapper<CmsPost> lw = new LambdaQueryWrapper<CmsPost>()
                .eq(CmsPost::getSlug, slug.trim())
                .isNull(CmsPost::getDeleteTime);
        if (excludeId != null) {
            lw.ne(CmsPost::getId, excludeId);
        }
        if (postMapper.selectCount(lw) > 0) {
            throw new CmsException("URL 别名已存在：" + slug.trim());
        }
    }

    private CmsCategory resolveCategory(String slugOrId) {
        if (slugOrId.matches("\\d+")) {
            return categoryMapper.selectById(Long.parseLong(slugOrId));
        }
        return categoryMapper.selectOne(new LambdaQueryWrapper<CmsCategory>()
                .eq(CmsCategory::getSlug, slugOrId).last("LIMIT 1"));
    }

    private CmsTag resolveTag(String slugOrId) {
        if (slugOrId.matches("\\d+")) {
            return tagMapper.selectById(Long.parseLong(slugOrId));
        }
        return tagMapper.selectOne(new LambdaQueryWrapper<CmsTag>()
                .eq(CmsTag::getSlug, slugOrId).last("LIMIT 1"));
    }

    /**
     * 批量取文章的分类名（导出用：按主分类在前，其余按 id 顺序）
     * <p>与 fillCategoryAndTags 同一套「关联表优先、冗余列回退」口径。</p>
     */
    public Map<Long, List<String>> loadCategoryNames(List<CmsPost> posts) {
        Map<Long, List<String>> result = new HashMap<>();
        if (posts == null || posts.isEmpty()) {
            return result;
        }
        List<Long> postIds = new ArrayList<>();
        for (CmsPost p : posts) {
            postIds.add(p.getId());
        }
        Map<Long, List<Long>> categoryIdsByPost = new HashMap<>();
        postCategoryMapper.selectList(new LambdaQueryWrapper<CmsPostCategory>()
                        .in(CmsPostCategory::getPostId, postIds))
                .forEach(pc -> categoryIdsByPost
                        .computeIfAbsent(pc.getPostId(), k -> new ArrayList<>())
                        .add(pc.getCategoryId()));
        Set<Long> allCategoryIds = new HashSet<>();
        categoryIdsByPost.values().forEach(allCategoryIds::addAll);
        for (CmsPost p : posts) {
            if (!categoryIdsByPost.containsKey(p.getId()) && p.getCategoryId() != null) {
                categoryIdsByPost.put(p.getId(), List.of(p.getCategoryId()));
                allCategoryIds.add(p.getCategoryId());
            }
        }
        Map<Long, CmsCategory> categoryMap = new HashMap<>();
        if (!allCategoryIds.isEmpty()) {
            categoryMapper.selectBatchIds(allCategoryIds).forEach(c -> categoryMap.put(c.getId(), c));
        }
        for (CmsPost p : posts) {
            List<Long> ids = categoryIdsByPost.getOrDefault(p.getId(), List.of());
            List<Long> ordered = new ArrayList<>();
            if (p.getCategoryId() != null && ids.contains(p.getCategoryId())) {
                ordered.add(p.getCategoryId());
            }
            for (Long cid : ids) {
                if (!ordered.contains(cid)) {
                    ordered.add(cid);
                }
            }
            List<String> names = new ArrayList<>();
            for (Long cid : ordered) {
                CmsCategory c = categoryMap.get(cid);
                if (c != null) {
                    names.add(c.getName());
                }
            }
            result.put(p.getId(), names);
        }
        return result;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
