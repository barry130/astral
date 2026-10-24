package com.astral.cms.controller;

import com.astral.cms.dto.CommentAuditDto;
import com.astral.cms.entity.CmsComment;
import com.astral.cms.service.CmsCommentService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容管理插件管理端-评论控制器（审核制）
 */
@Slf4j
@Tag(name = "内容管理插件-管理端评论")
@RestController
@RequestMapping("/api/v1/admin/cms/comment")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsCommentController {

    @Resource
    private CmsCommentService commentService;

    @Operation(summary = "评论分页（status/targetType/keyword 筛选，含目标标题与 IP）")
    @GetMapping("/page")
    public Result<Page<CmsComment>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                         @RequestParam(defaultValue = "10") Integer pageSize,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String targetType,
                                         @RequestParam(required = false) String keyword) {
        return Result.success(commentService.adminPage(pageNum, pageSize, status, targetType, keyword));
    }

    @Operation(summary = "审核评论（approved/rejected）")
    @RequiresPermission(value = "admin:cms:comment:edit", name = "评论管理", description = "评论审核与删除")
    @PutMapping("/{id}/audit")
    public Result<Void> audit(@PathVariable Long id, @Valid @RequestBody CommentAuditDto dto) {
        commentService.audit(id, dto.getStatus());
        return Result.success();
    }

    @Operation(summary = "删除评论")
    @RequiresPermission(value = "admin:cms:comment:edit", name = "评论管理", description = "评论审核与删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        commentService.delete(id);
        return Result.success();
    }
}
