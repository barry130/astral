package com.astral.cms.service;

import com.astral.cms.common.CmsException;
import com.astral.cms.entity.CmsPage;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.entity.CmsPostTag;
import com.astral.cms.entity.CmsTag;
import com.astral.cms.mapper.CmsPageMapper;
import com.astral.cms.mapper.CmsPostMapper;
import com.astral.cms.mapper.CmsPostTagMapper;
import com.astral.cms.mapper.CmsTagMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 内容导出服务
 * <p>单篇/单个页面导出为带 YAML 头信息的 Markdown 文件；全站打包为 ZIP（按
 * posts/ 与 pages/ 分目录的 .md 文件树）。导出内容始终包含正文原文，
 * 供备份、迁移到其它静态博客或本地归档使用。</p>
 */
@Slf4j
@Service
public class CmsExportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 导出文件名的最大长度（Windows 路径长度与常见工具限制） */
    private static final int MAX_NAME_LEN = 60;

    @Resource
    private CmsPostMapper postMapper;
    @Resource
    private CmsPageMapper pageMapper;
    @Resource
    private CmsTagMapper tagMapper;
    @Resource
    private CmsPostTagMapper postTagMapper;
    @Resource
    private CmsPostService postService;

    /**
     * 单篇文章导出 Markdown（含 YAML 头信息）
     */
    public String exportPost(Long id) {
        CmsPost post = postService.adminDetail(id);
        return renderPostMarkdown(post);
    }

    /**
     * 单个页面导出 Markdown（含 YAML 头信息）
     */
    public String exportPage(Long id) {
        CmsPage page = pageMapper.selectById(id);
        if (page == null || page.getDeleteTime() != null) {
            throw new CmsException("页面不存在或已删除");
        }
        return renderPageMarkdown(page);
    }

    /**
     * 全站打包 ZIP：posts/*.md + pages/*.md + 一份 manifest.md 目录索引
     */
    public byte[] exportZip() {
        List<CmsPost> posts = postMapper.selectList(new LambdaQueryWrapper<CmsPost>()
                .isNull(CmsPost::getDeleteTime)
                .orderByDesc(CmsPost::getPublishedAt)
                .orderByDesc(CmsPost::getId));
        List<CmsPage> pages = pageMapper.selectList(new LambdaQueryWrapper<CmsPage>()
                .isNull(CmsPage::getDeleteTime)
                .orderByAsc(CmsPage::getSort).orderByAsc(CmsPage::getId));
        Map<Long, List<String>> tagNamesByPost = loadTagNames(posts);
        Map<Long, List<String>> categoryNamesByPost = postService.loadCategoryNames(posts);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            Set<String> used = new HashSet<>();
            StringBuilder manifest = new StringBuilder("# 内容导出\n\n")
                    .append("导出时间：").append(LocalDateTime.now().format(DATE_FMT))
                    .append("\n\n## 文章（").append(posts.size()).append(" 篇）\n\n");
            for (CmsPost post : posts) {
                String name = uniqueName(used, "posts/", post.getSlug(), post.getTitle(), post.getId());
                writeEntry(zip, name, renderPostMarkdown(post, tagNamesByPost, categoryNamesByPost));
                manifest.append("- [").append(post.getTitle()).append("](").append(name).append(")\n");
            }
            manifest.append("\n## 页面（").append(pages.size()).append(" 个）\n\n");
            for (CmsPage page : pages) {
                String name = uniqueName(used, "pages/", page.getSlug(), page.getTitle(), page.getId());
                writeEntry(zip, name, renderPageMarkdown(page));
                manifest.append("- [").append(page.getTitle()).append("](").append(name).append(")\n");
            }
            writeEntry(zip, "manifest.md", manifest.toString());
        } catch (IOException e) {
            log.error("[CmsPlugin] 导出 ZIP 失败", e);
            throw new CmsException("导出失败：" + e.getMessage());
        }
        return buffer.toByteArray();
    }

    /**
     * 导出全部文章为单个 Markdown（便于一次性粘贴到别处）
     */
    public String exportAllMarkdown() {
        List<CmsPost> posts = postMapper.selectList(new LambdaQueryWrapper<CmsPost>()
                .isNull(CmsPost::getDeleteTime)
                .orderByDesc(CmsPost::getPublishedAt)
                .orderByDesc(CmsPost::getId));
        Map<Long, List<String>> tagNamesByPost = loadTagNames(posts);
        Map<Long, List<String>> categoryNamesByPost = postService.loadCategoryNames(posts);
        StringBuilder sb = new StringBuilder("# 文章合集\n\n");
        for (CmsPost post : posts) {
            sb.append(renderPostMarkdown(post, tagNamesByPost, categoryNamesByPost)).append("\n\n---\n\n");
        }
        return sb.toString();
    }

    // ==================== 私有辅助 ====================

    private String renderPostMarkdown(CmsPost post) {
        return renderPostMarkdown(post, loadTagNames(List.of(post)),
                postService.loadCategoryNames(List.of(post)));
    }

    private String renderPostMarkdown(CmsPost post, Map<Long, List<String>> tagNames,
                                      Map<Long, List<String>> categoryNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(yamlString(post.getTitle())).append('\n');
        if (notBlank(post.getSlug())) {
            sb.append("slug: ").append(yamlString(post.getSlug())).append('\n');
        }
        sb.append("date: ").append(post.getPublishedAt() == null ? ""
                : post.getPublishedAt().format(DATE_FMT)).append('\n');
        sb.append("status: ").append(post.getStatus()).append('\n');
        List<String> cats = categoryNames.getOrDefault(post.getId(), List.of());
        if (!cats.isEmpty()) {
            sb.append("categories: ").append(yamlArray(cats)).append('\n');
        }
        List<String> tags = tagNames.getOrDefault(post.getId(), List.of());
        if (!tags.isEmpty()) {
            sb.append("tags: ").append(yamlArray(tags)).append('\n');
        }
        if (notBlank(post.getCover())) {
            sb.append("cover: ").append(yamlString(post.getCover())).append('\n');
        }
        if (notBlank(post.getSummary())) {
            sb.append("summary: ").append(yamlString(post.getSummary())).append('\n');
        }
        sb.append("---\n\n");
        sb.append(post.getContent() == null ? "" : post.getContent());
        if (!sb.toString().endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private String renderPageMarkdown(CmsPage page) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: ").append(yamlString(page.getTitle())).append('\n');
        if (notBlank(page.getSlug())) {
            sb.append("slug: ").append(yamlString(page.getSlug())).append('\n');
        }
        sb.append("date: ").append(page.getPublishedAt() == null ? ""
                : page.getPublishedAt().format(DATE_FMT)).append('\n');
        sb.append("status: ").append(page.getStatus()).append('\n');
        if (page.getSort() != null) {
            sb.append("sort: ").append(page.getSort()).append('\n');
        }
        sb.append("---\n\n");
        sb.append(page.getContent() == null ? "" : page.getContent());
        if (!sb.toString().endsWith("\n")) {
            sb.append('\n');
        }
        return sb.toString();
    }

    private Map<Long, List<String>> loadTagNames(List<CmsPost> posts) {
        Map<Long, List<String>> result = new HashMap<>();
        if (posts == null || posts.isEmpty()) {
            return result;
        }
        List<Long> postIds = posts.stream().map(CmsPost::getId).toList();
        List<CmsPostTag> links = postTagMapper.selectList(new LambdaQueryWrapper<CmsPostTag>()
                .in(CmsPostTag::getPostId, postIds));
        if (links.isEmpty()) {
            return result;
        }
        Map<Long, String> nameById = new HashMap<>();
        tagMapper.selectBatchIds(links.stream().map(CmsPostTag::getTagId).distinct().toList())
                .forEach(t -> nameById.put(t.getId(), t.getName()));
        for (CmsPostTag link : links) {
            String name = nameById.get(link.getTagId());
            if (name != null) {
                result.computeIfAbsent(link.getPostId(), k -> new ArrayList<>()).add(name);
            }
        }
        return result;
    }

    /**
     * 生成 ZIP 内的唯一文件名：优先 slug，其次标题，最后 id
     * <p>文件名里剔除 Windows 不允许的字符，并限制长度。</p>
     */
    private String uniqueName(Set<String> used, String dir, String slug, String title, Long id) {
        String base = notBlank(slug) ? slug.trim() : (notBlank(title) ? title.trim() : "untitled-" + id);
        base = sanitize(base);
        if (base.isEmpty()) {
            base = "untitled-" + id;
        }
        String name = dir + base + ".md";
        int i = 2;
        while (!used.add(name)) {
            name = dir + base + "-" + i + ".md";
            i++;
        }
        return name;
    }

    private String sanitize(String raw) {
        String s = raw.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "-")
                .replaceAll("\\s+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (s.length() > MAX_NAME_LEN) {
            s = s.substring(0, MAX_NAME_LEN);
        }
        return s;
    }

    /** YAML 字符串：统一单引号包裹，内部单引号转义为两个 */
    private String yamlString(String raw) {
        if (raw == null) {
            return "''";
        }
        return "'" + raw.replace("'", "''").replace("\n", " ") + "'";
    }

    private String yamlArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(yamlString(values.get(i)));
        }
        return sb.append(']').toString();
    }

    private void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
