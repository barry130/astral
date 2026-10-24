package com.astral.cms.controller;

import com.astral.cms.common.CmsException;
import com.astral.cms.dto.ShareCreateDto;
import com.astral.cms.entity.CmsShare;
import com.astral.cms.service.CmsShareService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 内容管理插件管理端-分享链接控制器
 * <p>分享链接让「私密」内容以只读方式开放给未登录访客，可随时撤销、可设过期。</p>
 */
@Slf4j
@Tag(name = "内容管理插件-管理端分享")
@RestController
@RequestMapping("/api/v1/admin/cms/share")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsShareController {

    @Resource
    private CmsShareService shareService;

    @Operation(summary = "全部分享链接")
    @GetMapping("/list")
    public Result<List<CmsShare>> list() {
        return Result.success(shareService.listAll());
    }

    @Operation(summary = "某个内容的分享链接")
    @GetMapping("/list-by-target")
    public Result<List<CmsShare>> listByTarget(@RequestParam String targetType,
                                               @RequestParam Long targetId) {
        return Result.success(shareService.listByTarget(targetType, targetId));
    }

    @Operation(summary = "创建分享链接")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @PostMapping
    public Result<CmsShare> create(@Valid @RequestBody ShareCreateDto dto) {
        return Result.success(shareService.create(dto.getTargetType(), dto.getTargetId(),
                dto.getExpireAt(), dto.getRemark(), currentAdminId()));
    }

    @Operation(summary = "撤销分享链接")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @DeleteMapping("/{id}")
    public Result<Void> revoke(@PathVariable Long id) {
        shareService.revoke(id);
        return Result.success();
    }

    @Operation(summary = "撤销某个内容的全部分享链接")
    @RequiresPermission(value = "admin:cms:content:edit", name = "内容编辑", description = "文章/分类/标签/页面的新增修改删除")
    @DeleteMapping("/by-target")
    public Result<Integer> revokeByTarget(@RequestParam String targetType,
                                          @RequestParam Long targetId) {
        return Result.success(shareService.revokeByTarget(targetType, targetId));
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
