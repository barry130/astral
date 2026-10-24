package com.astral.cms.controller;

import com.astral.cms.common.CmsException;
import com.astral.cms.dto.CommentSubmitDto;
import com.astral.cms.entity.CmsCategory;
import com.astral.cms.entity.CmsComment;
import com.astral.cms.entity.CmsPage;
import com.astral.cms.entity.CmsPost;
import com.astral.cms.entity.CmsShare;
import com.astral.cms.entity.CmsTag;
import com.astral.cms.service.CmsCategoryService;
import com.astral.cms.service.CmsCommentService;
import com.astral.cms.service.CmsPageService;
import com.astral.cms.service.CmsPostService;
import com.astral.cms.service.CmsShareService;
import com.astral.cms.service.CmsSettingService;
import com.astral.cms.service.CmsTagService;
import com.astral.common.result.Result;
import com.astral.common.util.ClientIp;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 内容管理插件前台公开接口（供 astral-blog 独立博客前台消费，可绑定单独域名）
 * <p>挂载 /api/v1/app/cms/**。宿主 AuthInterceptor 对 /api/v1/app/ 非 user 区匿名放行，
 * 故本控制器无需额外鉴权配置。</p>
 */
@Slf4j
@Tag(name = "内容管理插件-前台公开")
@RestController
@RequestMapping("/api/v1/app/cms")
public class AppCmsController {

    /** 可信代理列表（决定能否采信 X-Forwarded-For），来自 astral.plugins.cms.trusted-proxies */
    @Value("${astral.plugins.cms.trusted-proxies:" + ClientIp.DEFAULT_TRUSTED_PROXIES + "}")
    private String trustedProxies;

    @Resource
    private CmsPostService postService;
    @Resource
    private CmsCategoryService categoryService;
    @Resource
    private CmsTagService tagService;
    @Resource
    private CmsPageService pageService;
    @Resource
    private CmsCommentService commentService;
    @Resource
    private CmsSettingService settingService;
    @Resource
    private CmsShareService shareService;

    // ==================== 文章 ====================

    @Operation(summary = "文章分页（仅已发布；categorySlug/tagSlug 支持别名或数字ID）")
    @GetMapping("/post/page")
    public Result<Page<CmsPost>> postPage(@RequestParam(defaultValue = "1") Integer pageNum,
                                          @RequestParam(defaultValue = "10") Integer pageSize,
                                          @RequestParam(required = false) String categorySlug,
                                          @RequestParam(required = false) String tagSlug,
                                          @RequestParam(required = false) String keyword) {
        return Result.success(postService.publicPage(pageNum, pageSize, categorySlug, tagSlug, keyword));
    }

    @Operation(summary = "文章详情（数字按ID，否则按slug；浏览量+1）")
    @GetMapping("/post/{idOrSlug}")
    public Result<CmsPost> postDetail(@PathVariable String idOrSlug) {
        return Result.success(postService.publicDetail(idOrSlug));
    }

    // ==================== 分类 / 标签 ====================

    @Operation(summary = "分类树（含已发布文章数）")
    @GetMapping("/category/tree")
    public Result<List<CmsCategory>> categoryTree() {
        return Result.success(categoryService.publicTree());
    }

    @Operation(summary = "标签列表（含已发布文章数）")
    @GetMapping("/tag/list")
    public Result<List<CmsTag>> tagList() {
        return Result.success(tagService.publicList());
    }

    // ==================== 页面（Wiki） ====================

    @Operation(summary = "页面树（仅已发布，Wiki 目录）")
    @GetMapping("/page/tree")
    public Result<List<CmsPage>> pageTree() {
        return Result.success(pageService.publicTree());
    }

    @Operation(summary = "页面详情（含面包屑；浏览量+1）")
    @GetMapping("/page/{idOrSlug}")
    public Result<CmsPage> pageDetail(@PathVariable String idOrSlug) {
        return Result.success(pageService.publicDetail(idOrSlug));
    }

    // ==================== 分享链接 ====================

    @Operation(summary = "按分享令牌读取内容（含被分享的私密文章/页面，只读）")
    @GetMapping("/share/{token}")
    public Result<Map<String, Object>> share(@PathVariable String token) {
        CmsShare share = shareService.findValidByToken(token);
        if (share == null) {
            throw new CmsException(404, "分享链接不存在或已过期");
        }
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("targetType", share.getTargetType());
        data.put("targetId", share.getTargetId());
        data.put("expireAt", share.getExpireAt());
        if (CmsShare.TARGET_POST.equals(share.getTargetType())) {
            data.put("post", postService.publicDetailById(share.getTargetId()));
        } else {
            data.put("page", pageService.publicDetailById(share.getTargetId()));
        }
        return Result.success(data);
    }

    // ==================== 站点设置 ====================

    @Operation(summary = "站点公开设置（站点标题/描述/页脚与评论开关）")
    @GetMapping("/setting")
    public Result<Map<String, String>> setting() {
        return Result.success(settingService.getPublic());
    }

    // ==================== 评论 ====================

    @Operation(summary = "评论分页（仅已通过审核；不含隐私字段）")
    @GetMapping("/comment/page")
    public Result<Page<CmsComment>> commentPage(@RequestParam String targetType,
                                                @RequestParam Long targetId,
                                                @RequestParam(defaultValue = "1") Integer pageNum,
                                                @RequestParam(defaultValue = "20") Integer pageSize) {
        return Result.success(commentService.publicPage(targetType, targetId, pageNum, pageSize));
    }

    @Operation(summary = "匿名提交评论（限流：同IP 10分钟5条；受 comment_enabled / comment_need_review 开关控制）")
    @PostMapping("/comment")
    public Result<CmsComment> submitComment(@Valid @RequestBody CommentSubmitDto dto,
                                            HttpServletRequest request) {
        return Result.success(commentService.submit(dto, clientIp(request), request.getHeader("User-Agent")));
    }

    /** 客户端真实 IP（可信代理白名单口径，与全站一致） */
    private String clientIp(HttpServletRequest request) {
        return ClientIp.resolve(
                request.getHeader("X-Forwarded-For"),
                request.getHeader("X-Real-IP"),
                request.getRemoteAddr(),
                trustedProxies);
    }
}
