package com.astral.log.controller;

import com.astral.common.annotation.RequiresPermission;
import com.astral.common.util.PageQuery;
import com.astral.dao.entity.OperateLog;
import com.astral.log.service.OperateLogService;
import com.astral.common.result.Result;
import com.astral.common.util.CsvExportUtil;
import org.springframework.http.ResponseEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;

/**
 * 操作日志控制器
 * <p>提供操作日志的CRUD操作</p>
 */
@Tag(name = "操作日志表")
@RestController
@RequestMapping("/api/v1/admin/log/operate_log")
@RequiresPermission(value = "admin:log:view", name = "查看日志", description = "操作日志查询")
@RequiredArgsConstructor
public class OperateLogController {

    /** 操作日志服务 */
    private final OperateLogService operateLogService;

    /**
     * 分页查询操作日志列表
     *
     * @param pageNum 页码，默认1
     * @param pageSize 每页大小，默认10
     * @return 分页操作日志数据
     */
    @Operation(summary = "分页查询")
    @GetMapping("/page")
    public Result<Page<OperateLog>> page(@RequestParam(defaultValue = "1") Integer pageNum,
                                               @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<OperateLog> page = new Page<>(PageQuery.pageNum(pageNum), PageQuery.pageSize(pageSize));
        return Result.success(operateLogService.page(page));
    }

    /**
     * 导出操作日志 CSV（最近 1 万条，避免全表导出拖垮 1C2G 服务器）
     */
    @Operation(summary = "导出CSV")
    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCsv() {
        Page<OperateLog> page = operateLogService.page(
                new Page<>(1, 10000, false));
        byte[] csv = CsvExportUtil.build(
                new String[]{"ID", "用户ID", "用户名", "模块", "操作类型", "请求方法", "请求URL",
                        "IP", "状态", "耗时(ms)", "错误信息", "时间"},
                page.getRecords(), r -> new Object[]{r.getId(), r.getUserId(), r.getUsername(),
                        r.getModule(), r.getOperateType(), r.getRequestMethod(), r.getRequestUrl(),
                        r.getIp(), r.getStatus(), r.getExecuteTime(), r.getErrorMsg(), r.getCreateTime()});
        String filename = "operate-logs-" + java.time.LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + filename)
                .header("Content-Type", "text/csv;charset=UTF-8")
                .body(csv);
    }

    /**
     * 根据ID查询操作日志详情
     *
     * @param id 日志ID
     * @return 操作日志实体
     */
    @Operation(summary = "根据ID查询")
    @GetMapping("/{id}")
    public Result<OperateLog> getById(@PathVariable Long id) {
        return Result.success(operateLogService.getById(id));
    }

    /**
     * 创建操作日志
     *
     * @param entity 操作日志实体
     * @return 操作结果
     */
    @Operation(summary = "创建")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody OperateLog entity) {
        operateLogService.save(entity);
        return Result.success();
    }

    /**
     * 更新操作日志
     *
     * @param id 日志ID
     * @param entity 操作日志实体（包含更新后的字段）
     * @return 操作结果
     */
    @Operation(summary = "更新")
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody OperateLog entity) {
        entity.setId(id);
        operateLogService.updateById(entity);
        return Result.success();
    }

    /**
     * 删除操作日志
     *
     * @param id 日志ID
     * @return 操作结果
     */
    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        operateLogService.removeById(id);
        return Result.success();
    }
}
