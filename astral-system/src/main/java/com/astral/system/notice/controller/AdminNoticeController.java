package com.astral.system.notice.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.exception.BusinessException;
import com.astral.common.result.Result;
import com.astral.dao.entity.SysNotice;
import com.astral.system.notice.NoticeService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

import java.util.List;

/**
 * 通知管理 - 管理端控制器。
 * <p>挂载 {@code /api/v1/admin/message/**}（路径与原反馈插件版一字不变），
 * 由宿主 {@code AuthInterceptor} 的 Sa-Token 管理员鉴权 + 身份门禁保护，权限码沿用
 * {@code admin:message:view} / {@code admin:message:edit}。</p>
 *
 * <p>与 App 端的差异只在传输层：管理端走真实 HTTP 状态码，App 端恒 HTTP 200；
 * 两边返回的都是同一个 {@link Result}。</p>
 *
 * <p><b>权限码为什么不带插件前缀</b>：通知是宿主能力，即使反馈插件被禁用，
 * 管理端仍然要能发布公告/通知 —— 权限由 {@code PermissionRegistry} 扫描
 * {@code @RestController} 注解注册，与插件开关无关。</p>
 */
@Slf4j
@Tag(name = "通知管理-管理端")
@RestController
@RequestMapping("/api/v1/admin/message")
@RequiresPermission(value = "admin:message:view", name = "通知查看", description = "管理端收件箱/通知分页查看")
public class AdminNoticeController {

    @Resource
    private NoticeService noticeService;

    @Operation(summary = "管理端收件箱（顶栏铃铛数据源：不按 channel 过滤，广播+发给当前管理员的点对点）")
    @GetMapping("/inbox")
    public Result<List<SysNotice>> inbox() {
        return Result.success(noticeService.listAdminInbox(currentAdminId()));
    }

    /** 当前管理端登录用户 ID（宿主 Sa-Token；AuthInterceptor 已保证登录态，此处仅做防御） */
    private Long currentAdminId() {
        Object loginId = cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new BusinessException("AUTH001");
        }
        return Long.parseLong(loginId.toString());
    }

    @Operation(summary = "通知分页（channel 支持多选，逗号分隔平台值；notice_type/关键词/时间筛选）")
    @GetMapping("/page")
    public Result<Page<SysNotice>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                        @RequestParam(required = false) String channel,
                                        @RequestParam(required = false) String noticeType,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String startDate,
                                        @RequestParam(required = false) String endDate) {
        return Result.success(noticeService.page(pageNum, pageSize, channel, noticeType, keyword, startDate, endDate));
    }

    @Operation(summary = "发公告/通知（继承 qt 公告全字段 + channel/notice_type/user_id）")
    @RequiresPermission(value = "admin:message:edit", name = "通知编辑", description = "公告/通知的发布、编辑、删除")
    @PostMapping
    public Result<SysNotice> create(@RequestBody @Valid SysNotice notice) {
        return Result.success(noticeService.create(notice));
    }

    @Operation(summary = "编辑通知")
    @RequiresPermission(value = "admin:message:edit", name = "通知编辑", description = "公告/通知的发布、编辑、删除")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody @Valid SysNotice notice) {
        noticeService.update(id, notice);
        return Result.success();
    }

    @Operation(summary = "删除通知（物理删）")
    @RequiresPermission(value = "admin:message:edit", name = "通知编辑", description = "公告/通知的发布、编辑、删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noticeService.delete(id);
        return Result.success();
    }
}
