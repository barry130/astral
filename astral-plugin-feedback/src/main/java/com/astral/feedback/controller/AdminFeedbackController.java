package com.astral.feedback.controller;

import com.astral.common.result.Result;
import com.astral.feedback.dto.PublicDto;
import com.astral.feedback.dto.ReplyDto;
import com.astral.feedback.dto.StatusDto;
import com.astral.feedback.entity.Feedback;
import com.astral.feedback.entity.FeedbackReply;
import com.astral.feedback.service.FeedbackService;
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
import java.util.Map;

/**
 * 反馈插件管理端反馈控制器
 * <p>挂载 /api/v1/admin/feedback/**，由宿主 Sa-Token 管理员鉴权保护。</p>
 */
@Slf4j
@Tag(name = "反馈插件-管理端反馈")
@RestController
@RequestMapping("/api/v1/admin/feedback")
public class AdminFeedbackController {

    @Resource
    private FeedbackService feedbackService;

    @Operation(summary = "分页查询（status/type/keyword/startDate/endDate 筛选）")
    @GetMapping("/page")
    public Result<Page<Feedback>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "10") Integer pageSize,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String type,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String startDate,
                                       @RequestParam(required = false) String endDate) {
        return Result.success(feedbackService.page(pageNum, pageSize, status, type, keyword, startDate, endDate));
    }

    @Operation(summary = "详情（含设备/IP/版本）")
    @GetMapping("/{id}")
    public Result<Feedback> detail(@PathVariable Long id) {
        return Result.success(feedbackService.adminDetail(id));
    }

    @Operation(summary = "状态流转（校验合法流转，触发通知）")
    @PutMapping("/{id}/status")
    public Result<Void> changeStatus(@PathVariable Long id, @Valid @RequestBody StatusDto dto) {
        feedbackService.changeStatus(id, dto);
        return Result.success();
    }

    @Operation(summary = "公开切换")
    @PutMapping("/{id}/public")
    public Result<Void> changePublic(@PathVariable Long id, @RequestBody PublicDto dto) {
        feedbackService.changePublic(id, dto);
        return Result.success();
    }

    @Operation(summary = "软删")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        feedbackService.softDelete(id);
        return Result.success();
    }

    @Operation(summary = "回复列表")
    @GetMapping("/{id}/replies")
    public Result<List<FeedbackReply>> replies(@PathVariable Long id) {
        return Result.success(feedbackService.replies(null, id, true));
    }

    @Operation(summary = "管理端回复（同时通知提交人）")
    @PostMapping("/reply")
    public Result<FeedbackReply> reply(@Valid @RequestBody ReplyDto dto) {
        // 管理端身份：使用当前宿主登录用户ID
        Long adminId = currentAdminId();
        return Result.success(feedbackService.adminReply(adminId, dto));
    }

    @Operation(summary = "统计看板")
    @GetMapping("/stat")
    public Result<Map<String, Object>> stat() {
        return Result.success(feedbackService.stat());
    }

    /** 当前管理端登录用户ID（宿主 Sa-Token） */
    private Long currentAdminId() {
        Object loginId = cn.dev33.satoken.stp.StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new com.astral.feedback.common.FeedbackException(401, "登录状态已失效");
        }
        return Long.parseLong(loginId.toString());
    }
}
