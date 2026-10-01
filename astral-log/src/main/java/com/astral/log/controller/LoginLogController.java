package com.astral.log.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.LoginLog;
import com.astral.log.service.LoginLogService;
import com.astral.common.result.Result;
import com.astral.common.util.CsvExportUtil;
import org.springframework.http.ResponseEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 登录日志控制器
 * <p>提供登录日志的CRUD操作</p>
 */
@Tag(name = "登录日志表")
@RestController
@RequestMapping("/api/v1/admin/log/login_log")
@RequiresPermission(value = "admin:log:view", name = "查看日志", description = "登录日志查询")
@RequiredArgsConstructor
public class LoginLogController {

    /** 登录日志服务 */
    private final LoginLogService loginLogService;

    /**
     * 分页查询登录日志列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页登录日志数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<LoginLog>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<LoginLog> page = new Page<>(PageQuery.pageNum(pageNum), PageQuery.pageSize(pageSize));
        return Result.success(loginLogService.page(page));
    }

    /**
     * 导出登录日志 CSV（最近 1 万条）
     */
    @Operation(summary = "导出CSV")
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv() {
        Page<LoginLog> page = loginLogService.page(
                new Page<>(1, 10000, false));
        byte[] csv = CsvExportUtil.build(
                new String[]{"ID", "用户ID", "用户名", "登录类型", "IP", "归属地", "浏览器", "操作系统",
                        "状态", "消息", "登录时间"},
                page.getRecords(), r -> new Object[]{r.getId(), r.getUserId(), r.getUsername(),
                        r.getLoginType(), r.getIp(), r.getLocation(), r.getBrowser(), r.getOs(),
                        r.getStatus(), r.getMsg(), r.getLoginTime()});
        String filename = "login-logs-" + java.time.LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename)
                .header("Content-Type", "text/csv;charset=UTF-8")
                .body(csv);
    }

    /**
     * 根据ID查询登录日志详情
     *
     * @param id 日志ID
     * @return 登录日志实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<LoginLog> getById(@PathVariable Long id) {
        return Result.success(loginLogService.getById(id));
    }

    /**
     * 创建登录日志
     *
     * @param entity 登录日志实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@RequestBody LoginLog entity) {
        loginLogService.save(entity);
        return Result.success();
    }

    /**
     * 更新登录日志
     *
     * @param id 日志ID
     * @param entity 登录日志实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody LoginLog entity) {
        entity.setId(id);
        loginLogService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除登录日志
     *
     * @param id 日志ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        loginLogService.removeById(id);
        return Result.success();
    }
}
