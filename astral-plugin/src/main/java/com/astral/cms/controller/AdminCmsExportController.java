package com.astral.cms.controller;

import com.astral.cms.service.CmsExportService;
import com.astral.common.annotation.RequiresPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 内容管理插件管理端-导出控制器
 * <p>导出的是文件流本身（不是 Result 包装），便于浏览器直接下载：
 * 单篇导出 .md、全站导出 .zip（posts/ + pages/ 文件树）。</p>
 */
@Slf4j
@Tag(name = "内容管理插件-管理端导出")
@RestController
@RequestMapping("/api/v1/admin/cms/export")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsExportController {

    /** 下载文件名里的时间戳（含时分秒，避免同一天多次导出互相覆盖） */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @Resource
    private CmsExportService exportService;

    @Operation(summary = "导出单篇文章为 Markdown")
    @GetMapping("/post/{id}")
    public ResponseEntity<byte[]> exportPost(@PathVariable Long id) {
        String markdown = exportService.exportPost(id);
        return markdownResponse(markdown, "post-" + id);
    }

    @Operation(summary = "导出单个页面为 Markdown")
    @GetMapping("/page/{id}")
    public ResponseEntity<byte[]> exportPage(@PathVariable Long id) {
        String markdown = exportService.exportPage(id);
        return markdownResponse(markdown, "page-" + id);
    }

    @Operation(summary = "导出全站为 ZIP（posts/ + pages/ 文件树 + manifest.md）")
    @GetMapping("/zip")
    public ResponseEntity<byte[]> exportZip() {
        byte[] zip = exportService.exportZip();
        String filename = "astral-cms-" + LocalDateTime.now().format(STAMP) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8)
                                .build().toString())
                .body(zip);
    }

    @Operation(summary = "导出全部文章为单个 Markdown")
    @GetMapping("/markdown")
    public ResponseEntity<byte[]> exportAllMarkdown() {
        String markdown = exportService.exportAllMarkdown();
        return markdownResponse(markdown, "all-posts");
    }

    private ResponseEntity<byte[]> markdownResponse(String markdown, String baseName) {
        String filename = baseName + "-" + LocalDateTime.now().format(STAMP) + ".md";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8)
                                .build().toString())
                .body(markdown.getBytes(StandardCharsets.UTF_8));
    }
}
