package com.astral.feedback.controller;

import com.astral.common.result.Result;
import com.astral.feedback.entity.SysNotice;
import com.astral.feedback.service.FeedbackNoticeService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
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
 * 反馈插件管理端通知控制器
 * <p>挂载 /api/v1/admin/message/**，由宿主 Sa-Token 管理员鉴权保护。</p>
 */
@Slf4j
@Tag(name = "反馈插件-管理端通知")
@RestController
@RequestMapping("/api/v1/admin/message")
public class AdminMessageController {

    @Resource
    private FeedbackNoticeService noticeService;

    @Operation(summary = "管理端收件箱（顶栏铃铛数据源：不按 channel 过滤，广播+发给当前管理员的点对点）")
    @GetMapping("/inbox")
    public Result<List<SysNotice>> inbox() {
        return Result.success(noticeService.listAdminInbox(currentAdminId()));
    }

    /** 当前管理端登录用户ID（宿主 Sa-Token） */
    private Long currentAdminId() {
        Object loginId = cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new com.astral.feedback.common.FeedbackException(401, "登录状态已失效");
        }
        return Long.parseLong(loginId.toString());
    }

    @Operation(summary = "通知分页（channel/notice_type/关键词/时间筛选）")
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
    @PostMapping
    public Result<SysNotice> create(@RequestBody SysNotice notice) {
        return Result.success(noticeService.create(notice));
    }

    @Operation(summary = "编辑通知")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody SysNotice notice) {
        noticeService.update(id, notice);
        return Result.success();
    }

    @Operation(summary = "删除通知（物理删）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        noticeService.delete(id);
        return Result.success();
    }
}
