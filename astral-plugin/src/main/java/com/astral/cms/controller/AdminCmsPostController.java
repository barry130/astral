package com.astral.cms.controller;

import com.astral.cms.common.CmsException;
import com.astral.cms.dto.ContentStatusDto;
import com.astral.cms.dto.PostSaveDto;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.service.CmsPostService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容管理插件管理端-文章控制器
 * <p>挂载 /api/v1/admin/cms/post/**，由宿主 Sa-Token 管理员鉴权保护。</p>
 */
@Slf4j
@Tag(name = "内容管理插件-管理端文章")
@RestController
@RequestMapping("/api/v1/admin/cms/post")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsPostController {

    @Resource
    private CmsPostService postService;

    @Operation(summary = "文章分页（status/categoryId/tagId/keyword 筛选）")
    @GetMapping("/page")
    public Result<Page<CmsPost>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                      @RequestParam(defaultValue = "10") Integer pageSize,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Long categoryId,
                                      @RequestParam(required = false) Long tagId,
                                      @RequestParam(required = false) String keyword) {
        return Result.success(postService.adminPage(pageNum, pageSize, status, categoryId, tagId, keyword));
    }

    @Operation(summary = "文章详情（含分类与标签）")
    @GetMapping("/{id}")
    public Result<CmsPost> detail(@PathVariable Long id) {
        return Result.success(postService.adminDetail(id));
    }

    @Operation(summary = "新增文章")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PostMapping
    public Result<Long> create(@Valid @RequestBody PostSaveDto dto) {
        return Result.success(postService.create(dto, currentAdminId()));
    }

    @Operation(summary = "修改文章")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody PostSaveDto dto) {
        postService.update(id, dto);
        return Result.success();
    }

    @Operation(summary = "状态变更（draft/published/hidden）")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody ContentStatusDto dto) {
        postService.changeStatus(id, dto.getStatus());
        return Result.success();
    }

    @Operation(summary = "删除文章（软删）")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        postService.delete(id);
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
