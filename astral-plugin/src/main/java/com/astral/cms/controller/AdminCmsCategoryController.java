package com.astral.cms.controller;

import com.astral.cms.dto.CategorySaveDto;
import com.astral.cms.entity.CmsCategory;
import com.astral.cms.service.CmsCategoryService;
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
 * 内容管理插件管理端-分类控制器（树形）
 */
@Slf4j
@Tag(name = "内容管理插件-管理端分类")
@RestController
@RequestMapping("/api/v1/admin/cms/category")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsCategoryController {

    @Resource
    private CmsCategoryService categoryService;

    @Operation(summary = "分类树（全量，含已发布文章数）")
    @GetMapping("/tree")
    public Result<List<CmsCategory>> tree() {
        return Result.success(categoryService.adminTree());
    }

    @Operation(summary = "新增分类")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody CategorySaveDto dto) {
        return Result.success(categoryService.create(dto));
    }

    @Operation(summary = "修改分类")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody CategorySaveDto dto) {
        categoryService.update(id, dto);
        return Result.success();
    }

    @Operation(summary = "删除分类（须无子分类且无文章）")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return Result.success();
    }
}
