package com.astral.cms.controller;

import com.astral.cms.dto.TagSaveDto;
import com.astral.cms.entity.CmsTag;
import com.astral.cms.service.CmsTagService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内容管理插件管理端-标签控制器
 */
@Slf4j
@Tag(name = "内容管理插件-管理端标签")
@RestController
@RequestMapping("/api/v1/admin/cms/tag")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsTagController {

    @Resource
    private CmsTagService tagService;

    @Operation(summary = "标签列表（含关联已发布文章数）")
    @GetMapping("/list")
    public Result<List<CmsTag>> list() {
        return Result.success(tagService.adminList());
    }

    @Operation(summary = "新增标签")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody TagSaveDto dto) {
        return Result.success(tagService.create(dto));
    }

    @Operation(summary = "修改标签")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody TagSaveDto dto) {
        tagService.update(id, dto);
        return Result.success();
    }

    @Operation(summary = "删除标签（级联清理关联）")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return Result.success();
    }
}
