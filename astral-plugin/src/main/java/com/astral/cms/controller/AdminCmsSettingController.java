package com.astral.cms.controller;

import com.astral.cms.dto.SettingSaveDto;
import com.astral.cms.service.CmsSettingService;
import com.astral.common.annotation.RequiresPermission;
import com.astral.common.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 内容管理插件管理端-站点设置控制器
 * <p>键位：site_title / site_description / footer_text / comment_enabled / comment_need_review。</p>
 */
@Slf4j
@Tag(name = "内容管理插件-管理端站点设置")
@RestController
@RequestMapping("/api/v1/admin/cms/setting")
@RequiresPermission(value = "admin:cms:view", name = "内容管理查看", description = "文章/分类/标签/页面/评论/设置查看")
public class AdminCmsSettingController {

    @Resource
    private CmsSettingService settingService;

    @Operation(summary = "读取全部设置（默认值兜底）")
    @GetMapping("/list")
    public Result<Map<String, String>> list() {
        return Result.success(settingService.getAll());
    }

    @Operation(summary = "保存设置（KV 全量提交）")
    @RequiresPermission(value = "admin:cms:setting:edit", name = "站点设置", description = "站点标题/描述/页脚/评论开关等设置修改")
    @PutMapping
    public Result<Map<String, String>> update(@Valid @RequestBody SettingSaveDto dto) {
        settingService.updateAll(dto.getSettings());
        return Result.success(settingService.getAll());
    }
}
