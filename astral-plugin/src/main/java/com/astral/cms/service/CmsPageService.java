package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.common.CmsVisibility;
import com.astral.cms.dto.PageSaveDto;
import com.astral.cms.entity.CmsPage;
import com.astral.cms.mapper.CmsPageMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CMS 页面（Wiki 层级页面）服务
 */
@Service
public class CmsPageService {

    @Resource
    private CmsPageMapper pageMapper;

    // ==================== 管理端 ====================

    /**
     * 管理端页面树（全量，含草稿）
     */
    public List<CmsPage> adminTree() {
        List<CmsPage> all = pageMapper.selectList(new LambdaQueryWrapper<CmsPage>()
                .isNull(CmsPage::getDeleteTime)
                .orderByAsc(CmsPage::getSort).orderByAsc(CmsPage::getId));
        return buildTree(all);
    }

    public CmsPage adminDetail(Long id) {
        return getByIdNotNull(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(PageSaveDto dto, Long authorId) {
        String status = validateStatus(dto.getStatus());
        checkSlugUnique(dto.getSlug(), null);
        checkParent(dto.getParentId(), null);
        CmsPage page = new CmsPage();
        page.setTitle(dto.getTitle().trim());
        page.setSlug(blankToNull(dto.getSlug()));
        page.setContent(dto.getContent());
        page.setParentId(dto.getParentId());
        page.setSort(dto.getSort() == null ? 0 : dto.getSort());
        page.setStatus(status);
        page.setViews(0L);
        page.setAuthorId(authorId);
        page.setPublishedAt(dto.getPublishedAt());
        if (isPubliclyVisible(status) && page.getPublishedAt() == null) {
            page.setPublishedAt(LocalDateTime.now());
        }
        pageMapper.insert(page);
        return page.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, PageSaveDto dto) {
        CmsPage exist = getByIdNotNull(id);
        String status = validateStatus(dto.getStatus());
        checkSlugUnique(dto.getSlug(), id);
        checkParent(dto.getParentId(), id);
        LocalDateTime publishedAt = dto.getPublishedAt();
        if (isPubliclyVisible(status)
                && exist.getPublishedAt() == null && publishedAt == null) {
            publishedAt = LocalDateTime.now();
        }
        LambdaUpdateWrapper<CmsPage> uw = new LambdaUpdateWrapper<CmsPage>()
                .eq(CmsPage::getId, id)
                .set(CmsPage::getTitle, dto.getTitle().trim())
                .set(CmsPage::getSlug, blankToNull(dto.getSlug()))
                .set(CmsPage::getContent, dto.getContent())
                .set(CmsPage::getParentId, dto.getParentId())
                .set(CmsPage::getSort, dto.getSort() == null ? 0 : dto.getSort())
                .set(CmsPage::getStatus, status)
                .set(CmsPage::getPublishedAt, publishedAt);
        pageMapper.update(null, uw);
    }

    public void changeStatus(Long id, String status) {
        CmsPage exist = getByIdNotNull(id);
        String next = validateStatus(status);
        LambdaUpdateWrapper<CmsPage> uw = new LambdaUpdateWrapper<CmsPage>()
                .eq(CmsPage::getId, id)
                .set(CmsPage::getStatus, next);
        if (isPubliclyVisible(next) && exist.getPublishedAt() == null) {
            uw.set(CmsPage::getPublishedAt, LocalDateTime.now());
        }
        pageMapper.update(null, uw);
    }

    public void delete(Long id) {
        getByIdNotNull(id);
        Long childCount = pageMapper.selectCount(new LambdaQueryWrapper<CmsPage>()
                .eq(CmsPage::getParentId, id)
                .isNull(CmsPage::getDeleteTime));
        if (childCount > 0) {
            throw new CmsException("该页面下存在子页面，请先删除子页面");
        }
        pageMapper.update(null, new LambdaUpdateWrapper<CmsPage>()
                .eq(CmsPage::getId, id)
                .set(CmsPage::getDeleteTime, LocalDateTime.now()));
    }

    // ==================== 前台公开 ====================

    /**
     * 前台页面树（仅已发布）
     */
    public List<CmsPage> publicTree() {
        LambdaQueryWrapper<CmsPage> lw = new LambdaQueryWrapper<CmsPage>()
                .isNull(CmsPage::getDeleteTime);
        appendVisibilityFilter(lw);
        lw.orderByAsc(CmsPage::getSort).orderByAsc(CmsPage::getId);
        List<CmsPage> all = pageMapper.selectList(lw);
        all.forEach(p -> p.setContent(null));
        return buildTree(all);
    }

    /**
     * 前台页面详情（含面包屑、浏览量+1）
     */
    public CmsPage publicDetail(String idOrSlug) {
        CmsPage page;
        if (idOrSlug.matches("\\d+")) {
            page = pageMapper.selectById(Long.parseLong(idOrSlug));
        } else {
            page = pageMapper.selectOne(new LambdaQueryWrapper<CmsPage>()
                    .eq(CmsPage::getSlug, idOrSlug)
                    .isNull(CmsPage::getDeleteTime)
                    .last("LIMIT 1"));
        }
        if (page == null || page.getDeleteTime() != null
                || !CmsVisibility.isVisible(page.getStatus())) {
            throw new CmsException(404, "页面不存在或未发布");
        }
        pageMapper.update(null, new LambdaUpdateWrapper<CmsPage>()
                .eq(CmsPage::getId, page.getId())
                .setSql("views = views + 1"));
        page.setBreadcrumb(buildBreadcrumb(page));
        return page;
    }

    /**
     * 按ID取页面详情（供分享链接使用：跳过状态可见性校验，仅校验未删除）
     * <p>调用方必须先校验分享 token 有效。</p>
     */
    public CmsPage publicDetailById(Long id) {
        CmsPage page = pageMapper.selectById(id);
        if (page == null || page.getDeleteTime() != null) {
            throw new CmsException(404, "页面不存在或已删除");
        }
        page.setBreadcrumb(buildBreadcrumb(page));
        page.setChildren(new ArrayList<>());
        return page;
    }

    // ==================== 私有辅助 ====================

    private List<CmsPage.Crumb> buildBreadcrumb(CmsPage current) {
        List<CmsPage.Crumb> crumbs = new ArrayList<>();
        Long parentId = current.getParentId();
        int guard = 0;
        while (parentId != null && guard++ < 20) {
            CmsPage parent = pageMapper.selectById(parentId);
            if (parent == null || parent.getDeleteTime() != null) {
                break;
            }
            CmsPage.Crumb crumb = new CmsPage.Crumb();
            crumb.setId(parent.getId());
            crumb.setTitle(parent.getTitle());
            crumb.setSlug(parent.getSlug());
            crumbs.add(0, crumb);
            parentId = parent.getParentId();
        }
        return crumbs;
    }

    private List<CmsPage> buildTree(List<CmsPage> all) {
        Map<Long, List<CmsPage>> byParent = new LinkedHashMap<>();
        Set<Long> ids = all.stream().map(CmsPage::getId).collect(Collectors.toSet());
        List<CmsPage> roots = new ArrayList<>();
        for (CmsPage p : all) {
            Long pid = p.getParentId();
            if (pid == null || pid == 0L || !ids.contains(pid)) {
                roots.add(p);
            } else {
                byParent.computeIfAbsent(pid, k -> new ArrayList<>()).add(p);
            }
        }
        attachChildren(roots, byParent);
        return roots;
    }

    private void attachChildren(List<CmsPage> nodes, Map<Long, List<CmsPage>> byParent) {
        for (CmsPage node : nodes) {
            List<CmsPage> children = byParent.getOrDefault(node.getId(), List.of());
            node.setChildren(children);
            attachChildren(children, byParent);
        }
    }

    private CmsPage getByIdNotNull(Long id) {
        CmsPage page = pageMapper.selectById(id);
        if (page == null || page.getDeleteTime() != null) {
            throw new CmsException("页面不存在或已删除");
        }
        return page;
    }

    private String validateStatus(String status) {
        if (CmsPage.STATUS_DRAFT.equals(status) || CmsPage.STATUS_PUBLISHED.equals(status)
                || CmsPage.STATUS_PRIVATE.equals(status) || CmsPage.STATUS_HIDDEN.equals(status)) {
            return status;
        }
        throw new CmsException("不支持的内容状态：" + status);
    }

    private void checkSlugUnique(String slug, Long excludeId) {
        if (blankToNull(slug) == null) {
            return;
        }
        LambdaQueryWrapper<CmsPage> lw = new LambdaQueryWrapper<CmsPage>()
                .eq(CmsPage::getSlug, slug.trim())
                .isNull(CmsPage::getDeleteTime);
        if (excludeId != null) {
            lw.ne(CmsPage::getId, excludeId);
        }
        if (pageMapper.selectCount(lw) > 0) {
            throw new CmsException("URL 别名已存在：" + slug.trim());
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
                throw new CmsException("父页面不能选择自身或自身的子孙页面");
            }
            CmsPage parent = pageMapper.selectById(cur);
            if (parent == null || parent.getDeleteTime() != null) {
                throw new CmsException("父页面不存在或已删除");
            }
            cur = parent.getParentId();
        }
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * 前台可见性过滤：已发布始终可见；私密仅对登录的管理员可见
     */
    private void appendVisibilityFilter(LambdaQueryWrapper<CmsPage> lw) {
        if (CmsVisibility.canViewPrivate()) {
            lw.in(CmsPage::getStatus, CmsPage.STATUS_PUBLISHED, CmsPage.STATUS_PRIVATE);
        } else {
            lw.eq(CmsPage::getStatus, CmsPage.STATUS_PUBLISHED);
        }
    }

    /**
     * 该状态是否算「内容已就绪」，需要写入首次发布时间（published 与 private 都是）
     */
    private boolean isPubliclyVisible(String status) {
        return CmsPage.STATUS_PUBLISHED.equals(status) || CmsPage.STATUS_PRIVATE.equals(status);
    }
}
