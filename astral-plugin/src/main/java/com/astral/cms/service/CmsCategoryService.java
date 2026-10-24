package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.common.CmsVisibility;
import com.astral.cms.dto.CategorySaveDto;
import com.astral.cms.entity.CmsCategory;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.entity.CmsPostCategory;
import com.astral.cms.mapper.CmsCategoryMapper;
import com.astral.cms.mapper.CmsPostCategoryMapper;
import com.astral.cms.mapper.CmsPostMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CMS 分类服务（树形）
 */
@Service
public class CmsCategoryService {

    @Resource
    private CmsCategoryMapper categoryMapper;
    @Resource
    private CmsPostMapper postMapper;
    @Resource
    private CmsPostCategoryMapper postCategoryMapper;

    /**
     * 管理端分类树（全量，含可见文章数）
     */
    public List<CmsCategory> adminTree() {
        List<CmsCategory> all = categoryMapper.selectList(new LambdaQueryWrapper<CmsCategory>()
                .orderByAsc(CmsCategory::getSort).orderByAsc(CmsCategory::getId));
        Map<Long, Long> postCounts = countVisiblePostsByCategory();
        all.forEach(c -> c.setPostCount(postCounts.getOrDefault(c.getId(), 0L)));
        return buildTree(all);
    }

    /**
     * 前台分类树（含可见文章数：匿名仅统计已发布，登录后含私密）
     */
    public List<CmsCategory> publicTree() {
        return adminTree();
    }

    public Long create(CategorySaveDto dto) {
        checkSlugUnique(dto.getSlug(), null);
        checkParent(dto.getParentId(), null);
        CmsCategory category = new CmsCategory();
        category.setName(dto.getName().trim());
        category.setSlug(blankToNull(dto.getSlug()));
        category.setParentId(dto.getParentId());
        category.setSort(dto.getSort() == null ? 0 : dto.getSort());
        category.setDescription(blankToNull(dto.getDescription()));
        categoryMapper.insert(category);
        return category.getId();
    }

    public void update(Long id, CategorySaveDto dto) {
        CmsCategory exist = getByIdNotNull(id);
        if (exist.getName().equals(dto.getName()) && dto.getName().isBlank()) {
            throw new CmsException("分类名称不能为空");
        }
        checkSlugUnique(dto.getSlug(), id);
        checkParent(dto.getParentId(), id);
        LambdaUpdateWrapper<CmsCategory> uw = new LambdaUpdateWrapper<CmsCategory>()
                .eq(CmsCategory::getId, id)
                .set(CmsCategory::getName, dto.getName().trim())
                .set(CmsCategory::getSlug, blankToNull(dto.getSlug()))
                .set(CmsCategory::getParentId, dto.getParentId())
                .set(CmsCategory::getSort, dto.getSort() == null ? 0 : dto.getSort())
                .set(CmsCategory::getDescription, blankToNull(dto.getDescription()));
        categoryMapper.update(null, uw);
    }

    public void delete(Long id) {
        getByIdNotNull(id);
        Long childCount = categoryMapper.selectCount(new LambdaQueryWrapper<CmsCategory>()
                .eq(CmsCategory::getParentId, id));
        if (childCount > 0) {
            throw new CmsException("该分类下存在子分类，请先删除子分类");
        }
        long postCount = countPostsOfCategory(id);
        if (postCount > 0) {
            throw new CmsException("该分类下仍有 " + postCount + " 篇文章，请先调整文章分类");
        }
        categoryMapper.deleteById(id);
    }

    // ==================== 私有辅助 ====================

    /**
     * 统计引用该分类的未删除文章数（多分类关联表 + 主分类冗余列去重）
     */
    private long countPostsOfCategory(Long categoryId) {
        Set<Long> postIds = new HashSet<>(postCategoryMapper.selectList(
                        new LambdaQueryWrapper<CmsPostCategory>()
                                .eq(CmsPostCategory::getCategoryId, categoryId))
                .stream().map(CmsPostCategory::getPostId).toList());
        // 兼容尚无关联行、只有冗余列的历史数据
        postMapper.selectList(new LambdaQueryWrapper<CmsPost>()
                        .select(CmsPost::getId)
                        .eq(CmsPost::getCategoryId, categoryId)
                        .isNull(CmsPost::getDeleteTime))
                .forEach(p -> {
                    if (p != null) {
                        postIds.add(p.getId());
                    }
                });
        if (postIds.isEmpty()) {
            return 0L;
        }
        return postMapper.selectCount(new LambdaQueryWrapper<CmsPost>()
                .in(CmsPost::getId, postIds)
                .isNull(CmsPost::getDeleteTime));
    }

    /**
     * 每个分类下的可见文章数（按多分类关联表统计，兼容冗余列）
     */
    private Map<Long, Long> countVisiblePostsByCategory() {
        // 可见文章 id 集合（必须连 id 一起 select，避免 NULL 行被映射成 null 元素）
        // 匿名访客只算已发布；登录的管理员把私密文章一起算进来
        LambdaQueryWrapper<CmsPost> lw = new LambdaQueryWrapper<CmsPost>()
                .select(CmsPost::getId, CmsPost::getCategoryId)
                .isNull(CmsPost::getDeleteTime);
        if (CmsVisibility.canViewPrivate()) {
            lw.in(CmsPost::getStatus, CmsPost.STATUS_PUBLISHED, CmsPost.STATUS_PRIVATE);
        } else {
            lw.eq(CmsPost::getStatus, CmsPost.STATUS_PUBLISHED);
        }
        List<CmsPost> posts = postMapper.selectList(lw);
        Set<Long> publishedIds = new HashSet<>();
        Map<Long, Set<Long>> categoryToPosts = new HashMap<>();
        for (CmsPost p : posts) {
            if (p == null || p.getId() == null) {
                continue;
            }
            publishedIds.add(p.getId());
            if (p.getCategoryId() != null) {
                categoryToPosts.computeIfAbsent(p.getCategoryId(), k -> new HashSet<>()).add(p.getId());
            }
        }
        // 关联表优先：一篇多分类文章在每个所属分类下都计数
        if (!publishedIds.isEmpty()) {
            List<CmsPostCategory> links = postCategoryMapper.selectList(
                    new LambdaQueryWrapper<CmsPostCategory>()
                            .in(CmsPostCategory::getPostId, publishedIds));
            for (CmsPostCategory link : links) {
                categoryToPosts.computeIfAbsent(link.getCategoryId(), k -> new HashSet<>())
                        .add(link.getPostId());
            }
        }
        Map<Long, Long> counts = new HashMap<>();
        categoryToPosts.forEach((cid, ids) -> counts.put(cid, (long) ids.size()));
        return counts;
    }

    private List<CmsCategory> buildTree(List<CmsCategory> all) {
        Map<Long, List<CmsCategory>> byParent = new LinkedHashMap<>();
        Set<Long> ids = all.stream().map(CmsCategory::getId).collect(Collectors.toSet());
        List<CmsCategory> roots = new ArrayList<>();
        for (CmsCategory c : all) {
            Long pid = c.getParentId();
            if (pid == null || pid == 0L || !ids.contains(pid)) {
                roots.add(c);
            } else {
                byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(c);
            }
        }
        attachChildren(roots, byParent);
        return roots;
    }

    private void attachChildren(List<CmsCategory> nodes, Map<Long, List<CmsCategory>> byParent) {
        for (CmsCategory node : nodes) {
            List<CmsCategory> children = byParent.getOrDefault(node.getId(), List.of());
            node.setChildren(children);
            attachChildren(children, byParent);
        }
    }

    private void checkParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        Long cur = parentId;
        int guard = 0;
        while (cur != null && guard++ < 50) {
            if (cur.equals(selfId)) {
                throw new CmsException("父分类不能选择自身或自身的子孙分类");
            }
            CmsCategory parent = categoryMapper.selectById(cur);
            if (parent == null) {
                throw new CmsException("父分类不存在");
            }
            cur = parent.getParentId();
        }
    }

    private void checkSlugUnique(String slug, Long excludeId) {
        if (blankToNull(slug) == null) {
            return;
        }
        LambdaQueryWrapper<CmsCategory> lw = new LambdaQueryWrapper<CmsCategory>()
                .eq(CmsCategory::getSlug, slug.trim());
        if (excludeId != null) {
            lw.ne(CmsCategory::getId, excludeId);
        }
        if (categoryMapper.selectCount(lw) > 0) {
            throw new CmsException("URL 别名已存在：" + slug.trim());
        }
    }

    private CmsCategory getByIdNotNull(Long id) {
        CmsCategory category = categoryMapper.selectById(id);
        if (category == null) {
            throw new CmsException("分类不存在");
        }
        return category;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
