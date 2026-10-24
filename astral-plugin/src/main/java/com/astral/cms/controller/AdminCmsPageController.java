package com.astral.cms.controller;

import com.astral.cms.common.CmsException;
import com.astral.cms.dto.ContentStatusDto;
import com.astral.cms.dto.PageSaveDto;
import com.astral.cms.entity.CmsPage;
import com.astral.cms.service.CmsPageService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import cn.dev33.satoken.stp.StpUtil;
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
 * 内容管理插件管理端-页面控制器（Wiki 层级页面）
 */
@Slf4j
@Tag(name = "内容管理插件-管理端页面")
@RestController
@RequestMapping("/api/v1/admin/cms/page")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsPageController {

    @Resource
    private CmsPageService pageService;

    @Operation(summary = "页面树（全量，含草稿）")
    @GetMapping("/tree")
    public Result<List<CmsPage>> tree() {
        return Result.success(pageService.adminTree());
    }

    @Operation(summary = "页面详情")
    @GetMapping("/{id}")
    public Result<CmsPage> detail(@PathVariable Long id) {
        return Result.success(pageService.adminDetail(id));
    }

    @Operation(summary = "新增页面")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody PageSaveDto dto) {
        return Result.success(pageService.create(dto, currentAdminId()));
    }

    @Operation(summary = "修改页面")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PageSaveDto dto) {
        pageService.update(id, dto);
        return Result.success();
    }

    @Operation(summary = "状态变更（draft/published/hidden）")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody ContentStatusDto dto) {
        pageService.changeStatus(id, dto.getStatus());
        return Result.success();
    }

    @Operation(summary = "删除页面（软删，须无子页面）")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        pageService.delete(id);
        return Result.success();
    }

    /** 当前管理端登录用户ID（宿主 Sa-Token） */
    private Long currentAdminId() {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new CmsException(401, "登录状态已失效");
        }
        return Long.parseLong(loginId.toString());
    }
}
